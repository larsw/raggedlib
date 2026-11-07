package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.LibraryRole
import dev.raggedlib.calibre.Formats
import dev.raggedlib.config.ApiException
import dev.raggedlib.config.badRequest
import dev.raggedlib.config.notFound
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration

@RestController
@RequestMapping("/api")
class BookController(
    private val books: BookRepository,
    private val library: LibraryService,
    private val paths: LibraryPaths,
    private val details: BookDetailsService,
    private val access: Access,
) {
    private val sorts = setOf("title", "author", "publisher", "pubdate", "indexedAt", "id")

    /** Syncs [libraryId], or every library the caller curates. */
    @PostMapping("/library/sync")
    fun sync(@RequestParam(required = false) libraryId: Long?): SyncResult {
        val ids = if (libraryId != null) setOf(access.requireLibrary(libraryId, LibraryRole.CURATOR).id)
        else access.libraries(LibraryRole.CURATOR).ifEmpty { throw ApiException(HttpStatus.FORBIDDEN, "you curate no library") }
        return library.sync(ids)
    }

    @GetMapping("/books")
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(required = false) libraryId: Long?,
        @RequestParam(required = false) publisher: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(defaultValue = "title") sort: String,
    ): Page<Book> {
        if (sort.removePrefix("-") !in sorts) badRequest("sort must be one of $sorts")
        val st = status?.takeIf { it.isNotBlank() }?.let {
            runCatching { BookStatus.valueOf(it.uppercase()) }.getOrElse { badRequest("unknown status '$status'") }
        }
        return books.search(q, st, tag, page.coerceAtLeast(0), size.coerceIn(1, 500), sort, scope(libraryId), publisher)
    }

    @GetMapping("/books/{id}")
    fun get(@PathVariable id: Long): Book = access.requireBook(id).book

    @GetMapping("/books/{id}/details")
    fun details(@PathVariable id: Long): BookDetails {
        access.requireBook(id)
        return details.details(id).let { d -> if (d.duplicateOf != null && !access.canSeeBook(d.duplicateOf.id)) d.copy(duplicateOf = null) else d }
    }

    @PostMapping("/books/{id}/reindex")
    fun reindex(@PathVariable id: Long): Book {
        access.requireBook(id, LibraryRole.CURATOR)
        return library.reindex(id)
    }

    @GetMapping("/tags")
    fun tags(@RequestParam(required = false) libraryId: Long?): List<TagCount> = books.tags(scope(libraryId))

    @GetMapping("/publishers")
    fun publishers(@RequestParam(required = false) libraryId: Long?): List<TagCount> = books.publishers(scope(libraryId))

    /** [libraryId] if the caller can see it (else 404), or every library it can see. */
    private fun scope(libraryId: Long?): Set<Long> =
        if (libraryId != null) setOf(access.requireLibrary(libraryId).id) else access.libraries()

    @GetMapping("/books/{id}/cover")
    fun cover(@PathVariable id: Long): ResponseEntity<Resource> {
        val row = access.requireBook(id)
        val file = row.coverPath?.let { paths.resolve(row.book.libraryId, it) }?.takeIf { Files.isRegularFile(it) }
            ?: notFound("book $id has no cover")
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
            .lastModified(Files.getLastModifiedTime(file).toMillis())
            .body(FileSystemResource(file))
    }

    /** Streams the book file inline; supports HTTP Range (PDF viewers). */
    @GetMapping("/books/{id}/file")
    fun file(@PathVariable id: Long, @RequestParam(required = false) format: String?): ResponseEntity<Resource> {
        val row = access.requireBook(id)
        val fmt = format?.uppercase()
            ?: row.book.sourceFormat?.takeIf { it in row.formatPaths }
            ?: row.formatPaths.keys.firstOrNull()
            ?: notFound("book $id has no files")
        val rel = row.formatPaths[fmt] ?: notFound("book $id has no $fmt file (available: ${row.formatPaths.keys.joinToString()})")
        val file = paths.resolve(row.book.libraryId, rel).takeIf { Files.isRegularFile(it) }
            ?: notFound("file for book $id ($fmt) is missing on disk")
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, Formats.contentType(fmt))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(file.fileName.toString(), StandardCharsets.UTF_8).build().toString(),
            )
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
            .body(FileSystemResource(file))
    }
}
