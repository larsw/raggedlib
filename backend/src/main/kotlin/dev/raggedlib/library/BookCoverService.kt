package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.ApiScope
import dev.raggedlib.calibre.CalibreLibraryWriteLock
import dev.raggedlib.calibre.CalibreMetadataSource
import dev.raggedlib.calibre.CalibreMetadataWriter
import dev.raggedlib.calibre.Formats
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.config.badRequest
import dev.raggedlib.config.notFound
import dev.raggedlib.extract.ProcessRunner
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

@Service
class BookCoverService(
    private val access: Access,
    private val books: BookRepository,
    private val registry: LibraryRegistry,
    private val paths: LibraryPaths,
    private val props: RaggedlibProperties,
    private val processRunner: ProcessRunner,
    private val calibre: CalibreMetadataSource,
    private val writer: CalibreMetadataWriter,
    private val writeLock: CalibreLibraryWriteLock,
    private val activity: BookActivityRepository,
    private val tx: TransactionTemplate,
    private val details: BookDetailsService,
) {
    fun upload(bookId: Long, image: ByteArray): BookDetails {
        val library = editableLibrary(bookId)
        CoverImageValidator.validate(image)
        val tempCover = temporaryCover(image)
        try {
            return change(bookId, library.root, tempCover)
        } finally {
            tempCover.deleteIfExists()
        }
    }

    fun remove(bookId: Long): BookDetails {
        val library = editableLibrary(bookId)
        return change(bookId, library.root, null)
    }

    fun useFormat(bookId: Long, format: String): BookDetails {
        val library = editableLibrary(bookId)
        val normalized = format.uppercase()
        return writeLock.withLibrary(library.root) {
            access.requireScope(ApiScope.LIBRARY_EDIT)
            access.requireEditableBook(bookId)
            access.requireEditableLibrary(library.id)
            val current = books.find(bookId) ?: notFound("book $bookId not found")
            val relative = current.formatPaths[normalized]
                ?: badRequest("book has no $normalized format (available: ${current.formatPaths.keys.sorted().joinToString()})")
            val file = paths.resolve(current.book.libraryId, relative).takeIf(Files::isRegularFile)
                ?: notFound("file for book $bookId ($normalized) is missing on disk")
            val tempCover = temporaryImagePath(".jpg")
            try {
                val result = processRunner.run(
                    listOf(props.tools.ebookMeta, file.toString(), "--get-cover", tempCover.toString()),
                    props.tools.calibredbTimeout,
                )
                if (result.exitCode != 0 || !Files.isRegularFile(tempCover)) {
                    badRequest("Calibre could not extract a cover from the $normalized format")
                }
                val image = Files.readAllBytes(tempCover)
                CoverImageValidator.validate(image)
                updateCover(bookId, library.root, tempCover)
            } finally {
                tempCover.deleteIfExists()
            }
        }
    }

    private fun editableLibrary(bookId: Long) = run {
        access.requireScope(ApiScope.LIBRARY_EDIT)
        val book = access.requireEditableBook(bookId)
        access.requireEditableLibrary(book.book.libraryId)
    }

    private fun change(bookId: Long, libraryRoot: Path, cover: Path?): BookDetails =
        writeLock.withLibrary(libraryRoot) {
            access.requireScope(ApiScope.LIBRARY_EDIT)
            val authorized = access.requireEditableBook(bookId)
            access.requireEditableLibrary(authorized.book.libraryId)
            val current = books.find(bookId) ?: notFound("book $bookId not found")
            if (cover == null && current.coverPath == null) return@withLibrary details.details(bookId)
            updateCover(bookId, libraryRoot, cover)
        }

    private fun updateCover(bookId: Long, libraryRoot: Path, cover: Path?): BookDetails {
        val current = books.find(bookId) ?: notFound("book $bookId not found")
        val library = registry.byId(current.book.libraryId)
        val before = current.coverPath != null
        writer.setCover(library, current.book.calibreId, cover)
        val updated = calibre.find(libraryRoot, current.book.calibreId)
            ?: error("Calibre didn't return book ${current.book.calibreId} after updating its cover")
        tx.executeWithoutResult {
            books.updateMetadata(
                bookId,
                updated,
                current.book.sourceFormat ?: Formats.choose(updated.formats.keys),
                reset = false,
            )
            activity.recordEdit(
                bookId = bookId,
                libraryId = current.book.libraryId,
                calibreId = current.book.calibreId,
                title = updated.title,
                actor = access.activityActor(),
                changes = mapOf("cover" to mapOf("old" to if (before) "present" else "none", "new" to if (updated.coverPath != null) "present" else "none")),
                action = "EDIT_COVER",
            )
        }
        return details.details(bookId)
    }

    private fun temporaryCover(data: ByteArray): Path {
        val path = temporaryImagePath()
        Files.write(path, data)
        return path
    }

    private fun temporaryImagePath(suffix: String = ".img"): Path {
        Files.createDirectories(props.tmpDir)
        return Files.createTempFile(props.tmpDir, "raggedlib-cover-", suffix)
    }
}
