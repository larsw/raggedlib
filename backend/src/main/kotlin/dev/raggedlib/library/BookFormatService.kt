package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.ApiScope
import dev.raggedlib.calibre.CalibreLibraryWriteLock
import dev.raggedlib.calibre.CalibreRecord
import dev.raggedlib.calibre.CalibreMetadataSource
import dev.raggedlib.calibre.CalibreMetadataWriter
import dev.raggedlib.calibre.Formats
import dev.raggedlib.config.ApiException
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.config.badRequest
import dev.raggedlib.config.notFound
import dev.raggedlib.ingest.IngestService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

@Service
class BookFormatService(
    private val access: Access,
    private val books: BookRepository,
    private val props: RaggedlibProperties,
    private val calibre: CalibreMetadataSource,
    private val writer: CalibreMetadataWriter,
    private val writeLock: CalibreLibraryWriteLock,
    private val activity: BookActivityRepository,
    private val ingest: IngestService,
    private val tx: TransactionTemplate,
    private val details: BookDetailsService,
) {
    init {
        require(props.upload.maxFormatSize.toBytes() > 0) { "raggedlib.upload.max-format-size must be positive" }
    }

    fun add(bookId: Long, file: MultipartFile, replace: Boolean): BookDetails {
        val format = Formats.fromFilename(file.originalFilename.orEmpty())
            ?: badRequest("upload a Calibre ebook format")
        if (file.isEmpty) badRequest("format file is empty")
        if (file.size > props.upload.maxFormatSize.toBytes()) {
            badRequest("format file must be no larger than ${props.upload.maxFormatSize}")
        }
        val tempFile = temporaryFile(format)
        try {
            file.transferTo(tempFile)
            return addStaged(bookId, listOf(tempFile), replace)
        } finally {
            tempFile.deleteIfExists()
        }
    }

    fun addStaged(bookId: Long, files: List<Path>, replace: Boolean): BookDetails {
        if (files.isEmpty()) badRequest("choose at least one format")
        val formats = files.map { file ->
            val format = Formats.fromFilename(file.fileName.toString()) ?: badRequest("upload a Calibre ebook format")
            if (!Files.isRegularFile(file) || Files.size(file) == 0L) badRequest("format file is empty")
            if (Files.size(file) > props.upload.maxFormatSize.toBytes()) {
                badRequest("format file must be no larger than ${props.upload.maxFormatSize}")
            }
            format
        }
        if (formats.size != formats.toSet().size) badRequest("upload contains more than one file for the same format")
        val library = editableLibrary(bookId)
        return writeLock.withLibrary(library.root) {
            val current = editableBook(bookId, library.id)
            val replacedFormats = formats.filter { it in current.formatPaths }
            if (replacedFormats.isNotEmpty() && !replace) {
                throw ApiException(
                    HttpStatus.CONFLICT,
                    "book already has ${replacedFormats.joinToString()}; confirm replacement",
                )
            }
            files.zip(formats).forEach { (file, format) ->
                writer.addFormat(
                    library,
                    current.book.calibreId,
                    file,
                    replace = replace && format in current.formatPaths,
                )
            }
            val updated = readBack(library.root, current.book.calibreId)
            val currentSource = current.book.sourceFormat ?: Formats.choose(current.formatPaths.keys)
            val nextSource = Formats.choose(updated.formats.keys)
            val reindex = nextSource != currentSource || currentSource in replacedFormats
            save(
                current,
                updated,
                nextSource,
                reindex,
                if (replacedFormats.isNotEmpty()) "REPLACE_FORMAT" else "ADD_FORMAT",
            )
            if (reindex) ingest.enqueue(bookId)
            details.details(bookId)
        }
    }

    fun remove(bookId: Long, format: String): BookDetails {
        val normalized = format.uppercase()
        if (!normalized.matches(Regex("[A-Z0-9]{1,10}"))) badRequest("format must be a file extension")
        val library = editableLibrary(bookId)
        return writeLock.withLibrary(library.root) {
            val current = editableBook(bookId, library.id)
            if (normalized !in current.formatPaths) badRequest("book has no $normalized format")
            writer.removeFormat(library, current.book.calibreId, normalized)
            val updated = readBack(library.root, current.book.calibreId)
            val currentSource = current.book.sourceFormat ?: Formats.choose(current.formatPaths.keys)
            val nextSource = Formats.choose(updated.formats.keys)
            val reindex = updated.formats.isNotEmpty() && nextSource != currentSource
            save(current, updated, nextSource, reindex, "REMOVE_FORMAT")
            if (updated.formats.isEmpty()) ingest.noFormats(bookId)
            else if (reindex) ingest.enqueue(bookId)
            details.details(bookId)
        }
    }

    private fun editableLibrary(bookId: Long) = run {
        access.requireScope(ApiScope.LIBRARY_EDIT)
        val book = access.requireEditableBook(bookId)
        access.requireEditableLibrary(book.book.libraryId)
    }

    private fun editableBook(bookId: Long, libraryId: Long): BookRow {
        access.requireScope(ApiScope.LIBRARY_EDIT)
        access.requireEditableBook(bookId)
        access.requireEditableLibrary(libraryId)
        return books.find(bookId) ?: notFound("book $bookId not found")
    }

    private fun save(
        current: BookRow,
        updated: CalibreRecord,
        sourceFormat: String?,
        reindex: Boolean,
        action: String,
    ) {
        tx.executeWithoutResult {
            books.updateMetadata(current.book.id, updated, sourceFormat, reset = reindex)
            activity.recordEdit(
                bookId = current.book.id,
                libraryId = current.book.libraryId,
                calibreId = current.book.calibreId,
                title = updated.title,
                actor = access.activityActor(),
                changes = mapOf(
                    "formats" to mapOf(
                        "old" to current.formatPaths.keys.sorted(),
                        "new" to updated.formats.keys.sorted(),
                    ),
                ),
                action = action,
            )
        }
    }

    private fun readBack(libraryRoot: Path, calibreId: Long) =
        calibre.find(libraryRoot, calibreId) ?: error("Calibre didn't return book $calibreId after updating its formats")

    private fun temporaryFile(format: String): Path {
        Files.createDirectories(props.tmpDir)
        return Files.createTempFile(props.tmpDir, "raggedlib-format-", ".$format")
    }
}
