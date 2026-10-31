package dev.raggedlib.ingest

import dev.raggedlib.library.BookStatus
import java.time.Instant

data class IngestStartRequest(val bookIds: List<Long>? = null, val retryFailed: Boolean? = false)

data class ActiveBook(val bookId: Long, val title: String, val stage: String, val chunks: Int?)

data class IngestError(val bookId: Long, val title: String, val error: String, val at: Instant)

data class IngestStatus(
    val running: Boolean,
    val stopRequested: Boolean,
    val startedAt: Instant?,
    val counts: Map<BookStatus, Int>,
    val totalBooks: Int,
    val totalChunks: Long,
    val sessionProcessed: Int,
    val sessionChunks: Long,
    val chunksPerSecond: Double,
    val etaSeconds: Long?,
    val active: List<ActiveBook>,
    val recentErrors: List<IngestError>,
)
