package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.ApiScope
import dev.raggedlib.auth.LibraryRole
import dev.raggedlib.calibre.CalibreLibraryWriteLock
import dev.raggedlib.calibre.CalibreMetadataSource
import dev.raggedlib.calibre.CalibreMetadataWriter
import dev.raggedlib.calibre.Formats
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.config.badRequest
import dev.raggedlib.config.notFound
import dev.raggedlib.ingest.IngestService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
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

internal fun groupUploadFiles(files: List<MultipartFile>, maxBytes: Long, maxSizeLabel: String): List<UploadGroup> {
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
        if (part.size > maxBytes) badRequest("file '$name' must be no larger than $maxSizeLabel")
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

@Service
class BookUploadService(
    private val access: Access,
    private val props: RaggedlibProperties,
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

    init {
        require(props.upload.maxFormatSize.toBytes() > 0) { "raggedlib.upload.max-format-size must be positive" }
    }

    fun add(libraryId: Long, files: List<MultipartFile>): BookUploadResult {
        access.requireScope(ApiScope.LIBRARY_EDIT)
        val target = access.requireEditableLibrary(libraryId)
        val groups = groupUploadFiles(files, props.upload.maxFormatSize.toBytes(), props.upload.maxFormatSize.toString())
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
                    if (actualSize > props.upload.maxFormatSize.toBytes()) {
                        badRequest("file '${file.name}' must be no larger than ${props.upload.maxFormatSize}")
                    }
                }
                directory
            }

            val calibreIds = mutableListOf<Long?>()
            var addFailure: Exception? = null
            try {
                writeLock.withLibrary(target.root) {
                    access.requireScope(ApiScope.LIBRARY_EDIT)
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
                val synced = library.syncAndFindBooks(libraryId, addedIds)
                tx.executeWithoutResult {
                    synced.values.forEach { book ->
                        activity.recordEdit(
                            bookId = book.id,
                            libraryId = book.libraryId,
                            calibreId = book.calibreId,
                            title = book.title,
                            actor = actor,
                            changes = mapOf("formats" to mapOf("old" to emptyList<String>(), "new" to book.formats)),
                            action = "UPLOAD",
                        )
                    }
                }
                synced.values.forEach { ingest.enqueue(it.id) }
                synced
            } catch (e: Exception) {
                if (addFailure == null) throw e
                addFailure.addSuppressed(e)
                emptyMap()
            }
            addFailure?.let { throw it }

            val items = groups.indices.map { index ->
                val group = groups[index]
                val calibreId = calibreIds[index]
                val book = calibreId?.let { addedBooks[it] }
                    ?: if (calibreId == null) null else error("synced book $calibreId was not returned")
                if (calibreId != null) {
                    BookUploadItem(group.files.map { it.name }, book, alreadyInLibrary = false)
                } else {
                    val identity = try {
                        metadata.identity(directories[index].resolve(group.files.first().name), group.name)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    } catch (e: Exception) {
                        log.warn("Couldn't read metadata for duplicate upload '{}': {}", group.name, e.message)
                        null
                    }
                    val pendingId = pending.retain(libraryId, directories[index], group.files)
                    var lookupError = if (identity == null) "Could not read the uploaded book's title and authors." else null
                    val duplicateBooks = if (identity == null) emptyList() else try {
                        library.syncAndFindMatchingBooks(libraryId, identity)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    } catch (e: Exception) {
                        log.warn("Couldn't find duplicate upload '{}' in library '{}': {}", group.name, target.name, e.message)
                        lookupError = "Could not find the matching book in the library."
                        emptyList()
                    }
                    BookUploadItem(
                        group.files.map { it.name },
                        book = null,
                        alreadyInLibrary = true,
                        duplicateBooks = duplicateBooks,
                        pendingId = pendingId,
                        duplicateLookupError = lookupError,
                    )
                }
            }

            return BookUploadResult(
                items,
            )
        } finally {
            deleteTree(staging)
        }
    }

    fun decide(libraryId: Long, pendingId: String, decision: BookUploadDecision): BookUploadDecisionResult {
        access.requireScope(ApiScope.LIBRARY_EDIT)
        access.requireLibrary(libraryId, LibraryRole.CURATOR)
        return pending.withPending(libraryId, pendingId) { upload ->
            if (decision.choice == BookUploadChoice.SKIP) {
                pending.discard(upload.id)
                return@withPending BookUploadDecisionResult(decision.choice, null)
            }
            val target = access.requireEditableLibrary(libraryId)
            val book = when (decision.choice) {
                BookUploadChoice.ADD_FORMAT, BookUploadChoice.REPLACE_FORMAT -> {
                    val bookId = decision.bookId ?: badRequest("choose a matching book")
                    if (books.find(bookId)?.book?.libraryId != libraryId) notFound("book $bookId not found")
                    formats.addStaged(
                        bookId,
                        upload.files.map { it.path },
                        replace = decision.choice == BookUploadChoice.REPLACE_FORMAT,
                    ).book
                }
                BookUploadChoice.ADD_SEPARATE -> {
                    val calibreId = writeLock.withLibrary(target.root) {
                        access.requireScope(ApiScope.LIBRARY_EDIT)
                        access.requireEditableLibrary(libraryId)
                        writer.addBook(target, upload.files.first().path.parent, allowDuplicates = true)
                            ?: error("calibredb refused an upload requested as a separate book")
                    }
                    val added = library.syncAndFindBooks(libraryId, setOf(calibreId)).getValue(calibreId)
                    recordUpload(added, access.activityActor())
                    ingest.enqueue(added.id)
                    added
                }
                BookUploadChoice.SKIP -> error("skip decisions are handled before library write checks")
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
                action = "UPLOAD",
            )
        }
    }

    private fun deleteTree(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
