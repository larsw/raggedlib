package dev.raggedlib.library

import dev.raggedlib.calibre.Formats
import dev.raggedlib.config.badRequest
import dev.raggedlib.config.notFound
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
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
) {
    private val sorts = setOf("title", "author", "pubdate", "indexedAt", "id")

    @PostMapping("/library/sync")
    fun sync(): SyncResult = library.sync()

    @GetMapping("/books")
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) tag: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(defaultValue = "title") sort: String,
    ): Page<Book> {
        if (sort.removePrefix("-") !in sorts) badRequest("sort must be one of $sorts")
        val st = status?.takeIf { it.isNotBlank() }?.let {
            runCatching { BookStatus.valueOf(it.uppercase()) }.getOrElse { badRequest("unknown status '$status'") }
        }
        return books.search(q, st, tag, page.coerceAtLeast(0), size.coerceIn(1, 500), sort)
    }

    @GetMapping("/books/{id}")
    fun get(@PathVariable id: Long): Book = (books.find(id) ?: notFound("book $id not found")).book

    @PostMapping("/books/{id}/reindex")
    fun reindex(@PathVariable id: Long): Book = library.reindex(id)

    @GetMapping("/tags")
    fun tags(): List<TagCount> = books.tags()

    @GetMapping("/books/{id}/cover")
    fun cover(@PathVariable id: Long): ResponseEntity<Resource> {
        val row = books.find(id) ?: notFound("book $id not found")
        val file = row.coverPath?.let(paths::resolve)?.takeIf { Files.isRegularFile(it) } ?: notFound("book $id has no cover")
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
            .lastModified(Files.getLastModifiedTime(file).toMillis())
            .body(FileSystemResource(file))
    }

    /** Streams the book file inline; supports HTTP Range (PDF viewers). */
    @GetMapping("/books/{id}/file")
    fun file(@PathVariable id: Long, @RequestParam(required = false) format: String?): ResponseEntity<Resource> {
        val row = books.find(id) ?: notFound("book $id not found")
        val fmt = format?.uppercase()
            ?: row.book.sourceFormat?.takeIf { it in row.formatPaths }
            ?: row.formatPaths.keys.firstOrNull()
            ?: notFound("book $id has no files")
        val rel = row.formatPaths[fmt] ?: notFound("book $id has no $fmt file (available: ${row.formatPaths.keys.joinToString()})")
        val file = paths.resolve(rel).takeIf { Files.isRegularFile(it) } ?: notFound("file for book $id ($fmt) is missing on disk")
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
