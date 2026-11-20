package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.ApiScope
import dev.raggedlib.auth.LibraryRole
import dev.raggedlib.calibre.CalibreLibraryWriteLock
import dev.raggedlib.calibre.CalibreMetadataSource
import dev.raggedlib.calibre.CalibreMetadataWriter
import dev.raggedlib.calibre.Formats
import dev.raggedlib.config.badRequest
import dev.raggedlib.config.label
import dev.raggedlib.config.notFound
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.ingest.IngestService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.util.unit.DataSize
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

data class BookUploadItem(
    val files: List<String>,
    val book: Book?,
    val alreadyInLibrary: Boolean,
    val duplicateBooks: List<Book> = emptyList(),
    val pendingId: String? = null,
    val duplicateLookupError: String? = null,
)

data class BookUploadResult(val items: List<BookUploadItem>)

enum class BookUploadChoice { ADD_FORMAT, REPLACE_FORMAT, ADD_SEPARATE, SKIP }

data class BookUploadDecision(val choice: BookUploadChoice, val bookId: Long? = null)

data class BookUploadDecisionResult(val choice: BookUploadChoice, val book: Book?)

internal data class UploadFile(
    val part: MultipartFile,
    val name: String,
    val format: String,
)

internal data class UploadGroup(
    val name: String,
    val files: List<UploadFile>,
)

internal fun groupUploadFiles(files: List<MultipartFile>, maxSize: DataSize): List<UploadGroup> {
    if (files.isEmpty()) badRequest("choose at least one book file")
    val groups = linkedMapOf<String, MutableList<UploadFile>>()
    for (part in files) {
        val suppliedName = part.originalFilename.orEmpty()
        val name = suppliedName.substringAfterLast('/').substringAfterLast('\\')
        if (name.isBlank() || name.any { Character.isISOControl(it) }) {
            badRequest("each uploaded file must have a valid filename")
        }
        val format = Formats.fromFilename(name) ?: badRequest("file '$name' is not a supported Calibre ebook format")
        val base = name.substringBeforeLast('.', "")
        if (base.isBlank() || base == "." || base == "..") badRequest("file '$name' must have a name before its extension")
        if (part.isEmpty) badRequest("file '$name' is empty")
        requireWithinLimit(name, part.size, maxSize)
        val key = base.lowercase(Locale.ROOT)
        groups.getOrPut(key) { mutableListOf() }.add(UploadFile(part, name, format))
    }
    return groups.map { (_, groupedFiles) ->
        val duplicateFormats = groupedFiles.groupBy { it.format }.filterValues { it.size > 1 }.keys
        if (duplicateFormats.isNotEmpty()) {
            badRequest("upload contains more than one ${duplicateFormats.sorted().joinToString()} file for '${groupedFiles.first().name.substringBeforeLast('.')}'")
        }
        UploadGroup(groupedFiles.first().name.substringBeforeLast('.'), groupedFiles)
    }
}

internal fun requireWithinLimit(name: String, bytes: Long, maxSize: DataSize) {
    if (bytes > maxSize.toBytes()) badRequest("file '$name' is larger than the ${maxSize.label()} limit")
}

/** [block]'s value, or null when it fails (logged as [what]); an interrupt is passed on, flag set again. */
private inline fun <T> orNullLogging(log: Logger, what: String, block: () -> T): T? = try {
    block()
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw e
} catch (e: Exception) {
    log.warn("{}: {}", what, e.message)
    null
}

@Service
class BookUploadService(
    private val access: Access,
    private val props: RaggedlibProperties,
    private val limits: UploadLimits,
    private val writer: CalibreMetadataWriter,
    private val metadata: CalibreMetadataSource,
    private val writeLock: CalibreLibraryWriteLock,
    private val library: LibraryService,
    private val books: BookRepository,
    private val formats: BookFormatService,
    private val activity: BookActivityRepository,
    private val ingest: IngestService,
    private val tx: TransactionTemplate,
    private val pending: PendingBookUploadStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun add(libraryId: Long, files: List<MultipartFile>): BookUploadResult {
        val target = access.requireEditableLibrary(libraryId)
        val groups = groupUploadFiles(files, limits.maxFormatSize)
        val actor = access.activityActor()
        Files.createDirectories(props.tmpDir)
        val staging = Files.createTempDirectory(props.tmpDir, "raggedlib-upload-")
        try {
            val directories = groups.mapIndexed { index, group ->
                val directory = Files.createDirectory(staging.resolve("book-${index + 1}"))
                for (file in group.files) {
                    val destination = directory.resolve(file.name)
                    file.part.transferTo(destination.toFile())
                    val actualSize = Files.size(destination)
                    if (actualSize == 0L) badRequest("file '${file.name}' is empty")
                    requireWithinLimit(file.name, actualSize, limits.maxFormatSize)
                }
                directory
            }

            // Books Calibre added before a failure are still synced, logged and indexed; then the failure is raised.
            val calibreIds = mutableListOf<Long?>()
            var addFailure: Exception? = null
            try {
                writeLock.withLibrary(target.root) {
                    access.requireEditableLibrary(libraryId)
                    directories.forEach { directory -> calibreIds += writer.addBook(target, directory) }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                addFailure = e
            } catch (e: Exception) {
                addFailure = e
            }

            val addedIds = calibreIds.filterNotNull().toCollection(LinkedHashSet())
            val addedBooks = try {
                library.syncAndFindBooks(libraryId, addedIds).also { synced ->
                    synced.values.forEach { book -> recordUpload(book, actor) }
                    synced.values.forEach { ingest.enqueue(it.id) }
                }
            } catch (e: Exception) {
                if (addFailure == null) throw e
                addFailure.addSuppressed(e)
                emptyMap()
            }
            addFailure?.let { throw it }

            val items = groups.mapIndexed { index, group ->
                when (val calibreId = calibreIds[index]) {
                    null -> duplicateItem(target, group, directories[index])
                    else -> BookUploadItem(
                        group.files.map { it.name },
                        addedBooks[calibreId] ?: error("synced book $calibreId was not returned"),
                        alreadyInLibrary = false,
                    )
                }
            }
            return BookUploadResult(items)
        } finally {
            deleteTree(staging)
        }
    }

    /** A group Calibre refused as a duplicate: kept for a decision, with the books it matches. */
    private fun duplicateItem(target: LibraryInfo, group: UploadGroup, directory: Path): BookUploadItem {
        val identity = orNullLogging(log, "Couldn't read metadata for duplicate upload '${group.name}'") {
            metadata.identity(directory.resolve(group.files.first().name), group.name)
        }
        val pendingId = pending.retain(target.id, directory, group.files)
        val duplicateBooks = identity?.let {
            orNullLogging(log, "Couldn't find duplicate upload '${group.name}' in library '${target.name}'") {
                library.syncAndFindMatchingBooks(target.id, identity)
            }
        }
        return BookUploadItem(
            group.files.map { it.name },
            book = null,
            alreadyInLibrary = true,
            duplicateBooks = duplicateBooks.orEmpty(),
            pendingId = pendingId,
            duplicateLookupError = when {
                identity == null -> "Could not read the uploaded book's title and authors."
                duplicateBooks == null -> "Could not find the matching book in the library."
                else -> null
            },
        )
    }

    /**
     * Skipping only discards the kept files, so it needs no write access: it works in a paused or read-only library.
     * The other choices change the library and need it writable and not paused.
     */
    fun decide(libraryId: Long, pendingId: String, decision: BookUploadDecision): BookUploadDecisionResult {
        access.requireScope(ApiScope.LIBRARY_EDIT)
        access.requireLibrary(libraryId, LibraryRole.CURATOR)
        return pending.withPending(libraryId, pendingId) { upload ->
            val book = when (decision.choice) {
                BookUploadChoice.SKIP -> null
                BookUploadChoice.ADD_FORMAT, BookUploadChoice.REPLACE_FORMAT -> {
                    access.requireEditableLibrary(libraryId)
                    val bookId = decision.bookId ?: badRequest("choose a matching book")
                    if (books.find(bookId)?.book?.libraryId != libraryId) notFound("book $bookId not found")
                    formats.addStaged(
                        bookId,
                        upload.files.map { it.path },
                        replace = decision.choice == BookUploadChoice.REPLACE_FORMAT,
                    ).book
                }
                BookUploadChoice.ADD_SEPARATE -> {
                    val target = access.requireEditableLibrary(libraryId)
                    val calibreId = writeLock.withLibrary(target.root) {
                        access.requireEditableLibrary(libraryId)
                        writer.addBook(target, upload.files.first().path.parent, allowDuplicates = true)
                            ?: error("calibredb refused an upload requested as a separate book")
                    }
                    val added = library.syncAndFindBooks(libraryId, setOf(calibreId)).getValue(calibreId)
                    recordUpload(added, access.activityActor())
                    ingest.enqueue(added.id)
                    added
                }
            }
            pending.discard(upload.id)
            BookUploadDecisionResult(decision.choice, book)
        }
    }

    private fun recordUpload(book: Book, actor: String) {
        tx.executeWithoutResult {
            activity.recordEdit(
                bookId = book.id,
                libraryId = book.libraryId,
                calibreId = book.calibreId,
                title = book.title,
                actor = actor,
                changes = mapOf("formats" to mapOf("old" to emptyList<String>(), "new" to book.formats)),
                action = BookActivityAction.UPLOAD,
            )
        }
    }
}
