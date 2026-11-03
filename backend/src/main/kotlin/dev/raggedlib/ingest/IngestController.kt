package dev.raggedlib.ingest

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ingest")
class IngestController(private val ingest: IngestService) {

    @PostMapping("/start")
    fun start(@RequestBody(required = false) body: IngestStartRequest?): IngestStatus =
        ingest.start(body?.bookIds, body?.retryFailed == true)

    /** Re-extracts indexed books (all, or [IngestReprocessRequest.bookIds] / formats), re-embedding only changed passages. */
    @PostMapping("/reprocess")
    fun reprocess(@RequestBody(required = false) body: IngestReprocessRequest?): IngestStatus =
        ingest.reprocess(body?.bookIds, body?.formats)

    /** Graceful by default (waits for books in flight); `force=true` interrupts them and requeues them as PENDING. */
    @PostMapping("/stop")
    fun stop(@RequestParam(defaultValue = "false") force: Boolean): IngestStatus =
        if (force) ingest.forceStop() else ingest.stop()

    @GetMapping("/status")
    fun status(): IngestStatus = ingest.status()
}
