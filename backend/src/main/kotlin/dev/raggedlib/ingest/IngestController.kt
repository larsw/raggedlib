package dev.raggedlib.ingest

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.LibraryRole
import dev.raggedlib.config.ApiException
import dev.raggedlib.library.LibraryRegistry
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Indexing is one background run over every library. Library admins start it for their libraries' books (others
 * stay pending until one of their admins does), and any library admin may stop it. Curators see the status (of
 * their libraries) but neither start, stop nor re-index books ([dev.raggedlib.library.BookController.reindex]).
 */
@RestController
@RequestMapping("/api/ingest")
class IngestController(
    private val ingest: IngestService,
    private val access: Access,
    private val registry: LibraryRegistry,
) {

    @PostMapping("/start")
    fun start(@RequestBody(required = false) body: IngestStartRequest?): IngestStatus {
        ingest.start(checkedBooks(body?.bookIds), body?.retryFailed == true, administeredOrAll())
        return status()
    }

    /** Re-extracts indexed books (all, or [IngestReprocessRequest.bookIds] / formats), re-embedding only changed passages. */
    @PostMapping("/reprocess")
    fun reprocess(@RequestBody(required = false) body: IngestReprocessRequest?): IngestStatus {
        ingest.reprocess(checkedBooks(body?.bookIds), body?.formats, administeredOrAll())
        return status()
    }

    /** Graceful by default (waits for books in flight); `force=true` interrupts them and requeues them as PENDING. */
    @PostMapping("/stop")
    fun stop(@RequestParam(defaultValue = "false") force: Boolean): IngestStatus {
        administered()
        if (force) ingest.forceStop() else ingest.stop()
        return status()
    }

    @GetMapping("/status")
    fun status(): IngestStatus = ingest.status(access.libraries().takeIf { it != allLibraries() })

    /** Libraries the caller administers; 403 if none. */
    private fun administered(): Set<Long> =
        access.libraries(LibraryRole.ADMIN).ifEmpty { throw ApiException(HttpStatus.FORBIDDEN, "only a library admin can start or stop indexing") }

    /** The libraries the caller administers, or null when that is every library. */
    private fun administeredOrAll(): Set<Long>? = administered().takeIf { it != allLibraries() }

    private fun allLibraries(): Set<Long> = registry.all.mapTo(HashSet()) { it.id }

    /** [requested] books, each of which must be in a library the caller administers (403 otherwise). */
    private fun checkedBooks(requested: List<Long>?): List<Long>? {
        requested?.distinct()?.forEach { access.requireBook(it, LibraryRole.ADMIN) }
        return requested
    }
}
