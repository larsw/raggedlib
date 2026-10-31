package dev.raggedlib.ingest

import dev.raggedlib.calibre.Formats
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.extract.Chunk
import dev.raggedlib.extract.Chunker
import dev.raggedlib.extract.Extraction
import dev.raggedlib.extract.TextExtractor
import dev.raggedlib.library.BookRepository
import dev.raggedlib.library.BookRow
import dev.raggedlib.library.BookStatus
import dev.raggedlib.library.LibraryPaths
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Background ingestion: N extraction workers (external processes) feed a bounded queue drained by a single
 * embedding consumer (the GPU is the bottleneck). Progress lives in the `books` table, so runs are resumable.
 */
@Service
class IngestService(
    private val props: RaggedlibProperties,
    private val books: BookRepository,
    private val chunks: ChunkStore,
    private val extractor: TextExtractor,
    private val embeddingModel: EmbeddingModel,
    private val paths: LibraryPaths,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Extracted(val row: BookRow, val format: String, val chunks: List<Chunk>)
    private data class Queued(val extracted: Extracted, val run: Run)

    private class Run(ids: List<Long>, val eligible: Set<BookStatus>) {
        val pending = ConcurrentLinkedQueue(ids)
        val extra = ConcurrentLinkedQueue<Long>()
        /** Set (under lock) once the coordinator stops accepting new books. */
        @Volatile var closed = false
        /** Books submitted for extraction and not yet finished (indexed/failed/skipped). */
        val inFlight = AtomicInteger()
        fun next(): Long? = extra.poll() ?: pending.poll()
        fun remaining(): Int = pending.size + extra.size
    }

    private val lock = Any()
    /** Books queued while a run was winding down; they start a new run afterwards. */
    private val deferred = LinkedHashSet<Long>()
    @Volatile private var run: Run? = null
    @Volatile private var stopRequested = false
    @Volatile private var startedAt: Instant? = null
    private val sessionProcessed = AtomicInteger()
    private val sessionChunks = AtomicLong()
    private val rate = RateMeter()
    private val active = ConcurrentHashMap<Long, ActiveBook>()
    private val recentErrors = ConcurrentLinkedDeque<IngestError>()
    private val chunker = with(props.chunking) { Chunker(targetChars, maxChars, overlapChars, minChars) }

    /** On startup, books stuck mid-pipeline lose their partial chunks and go back to PENDING. */
    @EventListener(ApplicationReadyEvent::class)
    fun recoverStuckBooks() {
        if (!props.ingest.recoverOnStartup) return
        val stuck = books.stuckIds()
        stuck.forEach { id ->
            tx.executeWithoutResult {
                chunks.deleteForBook(id)
                books.resetToPending(id)
            }
        }
        if (stuck.isNotEmpty()) log.info("Reset {} interrupted books to PENDING", stuck.size)
    }

    fun start(bookIds: List<Long>?, retryFailed: Boolean): IngestStatus {
        synchronized(lock) {
            if (run == null) {
                val eligible = buildSet {
                    add(BookStatus.PENDING)
                    if (retryFailed) add(BookStatus.FAILED)
                }
                val ids = books.idsWithStatus(eligible, bookIds?.distinct()?.takeIf { it.isNotEmpty() })
                val r = Run(ids, eligible)
                run = r
                stopRequested = false
                startedAt = Instant.now()
                sessionProcessed.set(0)
                sessionChunks.set(0)
                rate.reset()
                log.info("Ingestion started: {} books", ids.size)
                Thread.ofPlatform().name("ingest-coordinator").daemon(true).start { coordinate(r) }
            }
        }
        return status()
    }

    /** Queue a single (already reset to PENDING) book; starts a run restricted to it if idle. */
    fun enqueue(bookId: Long) {
        synchronized(lock) {
            val r = run
            when {
                r == null -> start(listOf(bookId), false)
                !r.closed && !stopRequested -> r.extra.add(bookId)
                !stopRequested -> deferred.add(bookId)
                else -> Unit // stays PENDING; picked up by the next run
            }
        }
    }

    fun stop(): IngestStatus {
        if (run != null) stopRequested = true
        return status()
    }

    fun isRunning() = run != null

    fun status(): IngestStatus {
        val counts = books.statusCounts()
        val r = run
        val perSec = if (r != null) rate.perSecond() else 0.0
        val eta = if (r != null && perSec > 0.01) {
            val avg = books.avgChunksPerIndexedBook() ?: 300.0
            val inFlightRemaining = active.values.sumOf { it.chunks ?: avg.toInt() }
            ((r.remaining() * avg + inFlightRemaining) / perSec).toLong()
        } else null
        return IngestStatus(
            running = r != null,
            stopRequested = r != null && stopRequested,
            startedAt = startedAt,
            counts = counts,
            totalBooks = counts.values.sum(),
            totalChunks = books.totalChunks(),
            sessionProcessed = sessionProcessed.get(),
            sessionChunks = sessionChunks.get(),
            chunksPerSecond = Math.round(perSec * 10) / 10.0,
            etaSeconds = eta,
            active = active.values.sortedBy { it.bookId },
            recentErrors = recentErrors.toList(),
        )
    }

    // ---------------------------------------------------------------------------------------------------------

    private fun coordinate(r: Run) {
        val queue = ArrayBlockingQueue<Any>(props.ingest.queueCapacity.coerceAtLeast(1))
        val consumer = Thread.ofPlatform().name("ingest-embedder").daemon(true).start { consume(queue) }
        val permits = Semaphore(props.ingest.extractConcurrency.coerceAtLeast(1))
        val workers = Executors.newVirtualThreadPerTaskExecutor()
        try {
            while (!stopRequested) {
                permits.acquire()
                if (stopRequested) {
                    permits.release()
                    break
                }
                val id = r.next()
                if (id == null) {
                    permits.release()
                    if (r.inFlight.get() > 0 && !stopRequested) {
                        // keep accepting reindex requests while books are still being processed
                        Thread.sleep(250)
                        continue
                    }
                    val done = synchronized(lock) {
                        if (r.extra.isEmpty()) r.closed = true
                        r.closed
                    }
                    if (done) break else continue
                }
                r.inFlight.incrementAndGet()
                workers.submit {
                    try {
                        val extracted = extractOne(id, r.eligible)
                        if (extracted != null) queue.put(Queued(extracted, r)) else r.inFlight.decrementAndGet()
                    } catch (e: Throwable) {
                        r.inFlight.decrementAndGet()
                        log.error("Extraction worker failed for book {}", id, e)
                    } finally {
                        permits.release()
                    }
                }
            }
            workers.shutdown()
            workers.awaitTermination(1, TimeUnit.DAYS)
            queue.put(POISON)
            consumer.join()
        } catch (e: Throwable) {
            log.error("Ingestion coordinator failed", e)
        } finally {
            active.clear()
            log.info("Ingestion finished: {} books, {} chunks", sessionProcessed.get(), sessionChunks.get())
            synchronized(lock) {
                r.closed = true
                run = null
                val again = if (stopRequested) emptyList() else deferred.toList()
                deferred.clear()
                stopRequested = false
                if (again.isNotEmpty()) start(again, false)
            }
        }
    }

    private fun extractOne(id: Long, eligible: Set<BookStatus>): Extracted? {
        val row = books.find(id) ?: return null
        if (row.book.status !in eligible && row.book.status != BookStatus.PENDING) return null
        val title = row.book.title
        val format = Formats.choose(row.formatPaths.keys)
        if (format == null) {
            finish(id) { books.setStatus(id, BookStatus.SKIPPED, "no supported format (${row.formatPaths.keys.joinToString()})") }
            return null
        }
        active[id] = ActiveBook(id, title, "EXTRACTING", null)
        books.setStatus(id, BookStatus.EXTRACTING, null, format)
        try {
            val file = paths.resolve(row.formatPaths.getValue(format))
            val started = System.nanoTime()
            when (val ex = extractor.extract(file, format)) {
                is Extraction.NoText -> {
                    active.remove(id)
                    finish(id) { books.setStatus(id, BookStatus.SKIPPED, ex.reason) }
                    return null
                }
                is Extraction.Text -> {
                    val chunkList = chunker.chunk(ex.paragraphs)
                    log.info("Extracted book {} '{}' ({}): {} chunks in {} ms", id, title.take(60), format, chunkList.size,
                        (System.nanoTime() - started) / 1_000_000)
                    if (chunkList.isEmpty()) {
                        active.remove(id)
                        finish(id) { books.setStatus(id, BookStatus.SKIPPED, "no usable text after cleanup") }
                        return null
                    }
                    active[id] = ActiveBook(id, title, "EXTRACTING", chunkList.size)
                    return Extracted(row, format, chunkList)
                }
            }
        } catch (e: Exception) {
            active.remove(id)
            fail(row, e)
            return null
        }
    }

    private fun consume(queue: ArrayBlockingQueue<Any>) {
        while (true) {
            val item = queue.take()
            if (item === POISON) return
            val (ex, r) = item as Queued
            val id = ex.row.book.id
            try {
                embedAndStore(ex)
            } catch (e: Exception) {
                fail(ex.row, e)
            } finally {
                active.remove(id)
                r.inFlight.decrementAndGet()
            }
        }
    }

    private fun embedAndStore(ex: Extracted) {
        val row = ex.row
        val id = row.book.id
        active[id] = ActiveBook(id, row.book.title, "EMBEDDING", ex.chunks.size)
        books.setStatus(id, BookStatus.EMBEDDING, null, ex.format)
        val inputs = ex.chunks.map { embeddingInput(row.book.title, it) }
        val batches = inputs.chunked(props.ingest.embedBatchSize.coerceAtLeast(1))
        val vectors = ArrayList<FloatArray>(inputs.size)
        val parallel = props.ingest.embedParallelism.coerceAtLeast(1)
        val embedStart = System.nanoTime()
        Executors.newVirtualThreadPerTaskExecutor().use { exec ->
            for (group in batches.chunked(parallel)) {
                val futures = group.map { batch -> exec.submit<List<FloatArray>> { embedWithRetry(batch) } }
                futures.forEach { f ->
                    val v = f.get()
                    vectors += v
                    rate.record(v.size)
                }
            }
        }
        val embedMs = (System.nanoTime() - embedStart) / 1_000_000
        log.info("Embedded book {}: {} chunks in {} ms ({} chunks/s)", id, vectors.size, embedMs,
            "%.1f".format(vectors.size * 1000.0 / embedMs.coerceAtLeast(1)))
        check(vectors.size == ex.chunks.size) { "embedding count mismatch" }
        vectors.firstOrNull()?.let {
            check(it.size == props.embedding.dimensions) { "embedding has ${it.size} dims, expected ${props.embedding.dimensions}" }
        }
        tx.executeWithoutResult {
            chunks.deleteForBook(id)
            chunks.insert(id, row.book.title, row.book.authors, ex.chunks, vectors)
            books.markIndexed(id, ex.chunks.size, ex.format)
        }
        sessionChunks.addAndGet(ex.chunks.size.toLong())
        sessionProcessed.incrementAndGet()
    }

    private fun embedWithRetry(batch: List<String>): List<FloatArray> {
        var attempt = 0
        while (true) {
            try {
                return embeddingModel.embed(batch)
            } catch (e: Exception) {
                if (++attempt >= 3) throw e
                log.warn("Embedding batch failed (attempt {}): {}", attempt, e.message)
                Thread.sleep(2_000L * attempt)
            }
        }
    }

    private fun finish(id: Long, update: () -> Unit) {
        update()
        sessionProcessed.incrementAndGet()
    }

    private fun fail(row: BookRow, e: Exception) {
        val msg = (e.message ?: e.javaClass.simpleName).take(1000)
        log.warn("Book {} '{}' failed: {}", row.book.id, row.book.title, msg)
        try {
            tx.executeWithoutResult {
                chunks.deleteForBook(row.book.id)
                books.setStatus(row.book.id, BookStatus.FAILED, msg)
            }
        } catch (dbErr: Exception) {
            log.error("Could not mark book {} as failed", row.book.id, dbErr)
        }
        sessionProcessed.incrementAndGet()
        recentErrors.addFirst(IngestError(row.book.id, row.book.title, msg, Instant.now()))
        while (recentErrors.size > 50) recentErrors.pollLast()
    }

    companion object {
        private val POISON = Any()

        /** Small context header so each chunk is self-describing for the embedding model. */
        fun embeddingInput(title: String, chunk: Chunk): String = buildString {
            append("Book: ").append(title).append('\n')
            if (chunk.section != null) append("Section: ").append(chunk.section).append('\n')
            append('\n').append(chunk.text)
        }
    }
}
