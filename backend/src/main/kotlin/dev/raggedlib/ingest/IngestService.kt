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
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
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
    private val events: ApplicationEventPublisher,
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

    /**
     * On startup, books stuck mid-pipeline go back to PENDING. Passages are replaced in one transaction, so a stuck
     * book still has its previous ones (if any), complete; they stay searchable until it is indexed again.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun recoverStuckBooks() {
        if (!props.ingest.recoverOnStartup) return
        val stuck = books.stuckIds()
        books.requeue(stuck)
        if (stuck.isNotEmpty()) log.info("Reset {} interrupted books to PENDING", stuck.size)
    }

    /**
     * Extracts [bookIds] (default: every INDEXED or DUPLICATE book, optionally only in [formats]) again, e.g. after
     * extraction improved. Passages whose text and section come out unchanged keep their embeddings, so only
     * changed ones cost GPU time; books turn out DUPLICATE (or stop being one) along the way.
     */
    fun reprocess(bookIds: List<Long>?, formats: List<String>?): IngestStatus {
        val wanted = formats?.map { it.uppercase() }?.toSet()
        val ids = books.idsWithStatus(listOf(BookStatus.INDEXED, BookStatus.DUPLICATE), bookIds?.distinct()?.takeIf { it.isNotEmpty() })
            .filter { id -> wanted == null || books.find(id)?.book?.sourceFormat?.uppercase() in wanted }
        // an empty list would make start() take every PENDING book
        if (ids.isEmpty()) return status()
        books.requeue(ids)
        log.info("Reprocessing {} books", ids.size)
        synchronized(lock) {
            if (run == null) start(ids, false) else ids.forEach(::enqueue)
        }
        return status()
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
            try {
                events.publishEvent(IngestionFinished(sessionProcessed.get(), sessionChunks.get()))
            } catch (e: Exception) {
                log.warn("Ingestion-finished listener failed: {}", e.message)
            }
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
            finish(id) { empty(id, BookStatus.SKIPPED, "no supported format (${row.formatPaths.keys.joinToString()})") }
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
                    finish(id) { empty(id, BookStatus.SKIPPED, ex.reason, format) }
                    return null
                }
                is Extraction.Text -> {
                    val chunkList = chunker.chunk(ex.paragraphs)
                    log.info("Extracted book {} '{}' ({}): {} chunks in {} ms", id, title.take(60), format, chunkList.size,
                        (System.nanoTime() - started) / 1_000_000)
                    if (chunkList.isEmpty()) {
                        active.remove(id)
                        finish(id) { empty(id, BookStatus.SKIPPED, "no usable text after cleanup", format) }
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
        if (storeAsDuplicate(ex)) return

        // Unchanged passages (same text, section and page, valid embedding) stay as they are: rewriting a row costs
        // an HNSW index insert, which is slower than the embedding itself. Other passages reuse the embedding of a
        // stored one with the same input (same title, section and text) where there is one.
        val stored = chunks.reusable(id, row.book.title)
        val unchanged = stored.groupByTo(HashMap()) { Triple(it.content, it.section, it.page) }
            .mapValues { ArrayDeque(it.value) }
        val keep = ArrayList<UUID>()
        val toWrite = ex.chunks.filter { c ->
            val match = unchanged[Triple(c.text, c.section, c.page)]?.removeFirstOrNull()
            if (match != null) keep += match.id
            match == null
        }
        val byInput = stored.associate { embeddingInput(row.book.title, Chunk(0, it.content, null, it.section)) to it.embedding }
        val inputs = toWrite.map { embeddingInput(row.book.title, it) }
        val vectors = arrayOfNulls<FloatArray>(inputs.size)
        inputs.forEachIndexed { i, input -> vectors[i] = byInput[input] }
        val missing = inputs.indices.filter { vectors[it] == null }
        val batches = missing.chunked(props.ingest.embedBatchSize.coerceAtLeast(1))
        val parallel = props.ingest.embedParallelism.coerceAtLeast(1)
        val embedStart = System.nanoTime()
        Executors.newVirtualThreadPerTaskExecutor().use { exec ->
            for (group in batches.chunked(parallel)) {
                val futures = group.map { batch -> batch to exec.submit<List<FloatArray>> { embedWithRetry(batch.map(inputs::get)) } }
                futures.forEach { (batch, f) ->
                    val v = f.get()
                    check(v.size == batch.size) { "embedding count mismatch" }
                    batch.forEachIndexed { j, i -> vectors[i] = v[j] }
                    rate.record(v.size)
                }
            }
        }
        val embedMs = (System.nanoTime() - embedStart) / 1_000_000
        log.info("Embedded book {}: {} chunks in {} ms ({} chunks/s); {} unchanged, {} rewritten with their embeddings, {} dropped",
            id, missing.size, embedMs, "%.1f".format(missing.size * 1000.0 / embedMs.coerceAtLeast(1)),
            keep.size, toWrite.size - missing.size, stored.size - keep.size)
        val all = vectors.map { checkNotNull(it) { "missing embedding" } }
        all.firstOrNull()?.let {
            check(it.size == props.embedding.dimensions) { "embedding has ${it.size} dims, expected ${props.embedding.dimensions}" }
        }
        val dependents = books.duplicatesOf(id)
        val storeStart = System.nanoTime()
        tx.executeWithoutResult {
            chunks.deleteForBookExcept(id, keep)
            chunks.insert(id, row.book.title, row.book.authors, toWrite, all)
            books.markIndexed(id, ex.chunks.size, ex.format)
        }
        log.debug("Stored book {} in {} ms", id, (System.nanoTime() - storeStart) / 1_000_000)
        sessionChunks.addAndGet(ex.chunks.size.toLong())
        sessionProcessed.incrementAndGet()
        // its text changed: books that were copies of it are decided again (cheap if they still are)
        if (dependents.isNotEmpty() && (toWrite.isNotEmpty() || keep.size != stored.size)) {
            books.requeue(dependents)
            dependents.forEach(::enqueue)
        }
        absorbShorterCopies(id, ex)
    }

    /**
     * The other direction of [storeAsDuplicate]: indexed books whose text is (nearly) all in the book just stored,
     * which is at least as long, become its duplicates — otherwise the order books happen to be indexed in would
     * decide whether a copy is caught.
     */
    private fun absorbShorterCopies(id: Long, ex: Extracted) {
        val minShare = props.ingest.duplicateMinShare
        if (minShare <= 0.0) return
        val hashes = ex.chunks.mapTo(HashSet()) { Duplicates.md5(it.text) }
        for (m in chunks.booksContaining(hashes, id)) {
            if (m.chunks > ex.chunks.size || m.shared < minShare * minOf(m.chunks, hashes.size)) continue
            val other = books.find(m.bookId)?.book ?: continue
            if (other.status == BookStatus.EXTRACTING || other.status == BookStatus.EMBEDDING) continue // decided on its own
            val distinct = chunks.distinctPassages(m.bookId)
            val keeper = Duplicates.keeper(m.bookId, m.chunks, distinct, listOf(Duplicates.Match(id, m.shared, ex.chunks.size)),
                minShare, MIN_SHARED) ?: continue
            val share = keeper.shared * 100 / distinct.coerceAtLeast(1)
            val marked = tx.execute {
                books.markDuplicate(m.bookId, id, "same text as “${ex.row.book.title}” (#$id): $share% of its passages", null)
                    .also { if (it) chunks.deleteForBook(m.bookId) }
            } == true
            if (marked) log.info("Book {} '{}' duplicates book {} '{}' ({}% of {} passages); its passages were dropped",
                m.bookId, other.title.take(60), id, ex.row.book.title.take(60), share, distinct)
        }
    }

    /**
     * If the extracted book duplicates another indexed one ([Duplicates]), marks it DUPLICATE without embedding it
     * and drops its own passages. Books that were duplicates of this one now point at that copy.
     */
    private fun storeAsDuplicate(ex: Extracted): Boolean {
        val minShare = props.ingest.duplicateMinShare
        if (minShare <= 0.0) return false
        val id = ex.row.book.id
        val hashes = ex.chunks.mapTo(HashSet()) { Duplicates.md5(it.text) }
        val keeper = Duplicates.keeper(id, ex.chunks.size, hashes.size, chunks.booksContaining(hashes, id), minShare, MIN_SHARED)
            ?: return false
        val keeperTitle = books.find(keeper.bookId)?.book?.title ?: "#${keeper.bookId}"
        val share = keeper.shared * 100 / hashes.size
        val marked = tx.execute {
            books.markDuplicate(id, keeper.bookId, "same text as “$keeperTitle” (#${keeper.bookId}): $share% of its passages", ex.format)
                .also { if (it) chunks.deleteForBook(id) }
        } == true
        if (!marked) return false // the copy was removed meanwhile: index this one
        log.info("Book {} '{}' duplicates book {} '{}' ({}% of {} passages); not indexed", id, ex.row.book.title.take(60),
            keeper.bookId, keeperTitle.take(60), share, hashes.size)
        sessionProcessed.incrementAndGet()
        return true
    }

    /** The book ends without passages ([status] SKIPPED or FAILED); its duplicates need passages of their own. */
    private fun empty(id: Long, status: BookStatus, reason: String, format: String? = null) {
        tx.executeWithoutResult {
            chunks.deleteForBook(id)
            books.markEmpty(id, status, reason, format)
        }
        requeueDuplicatesOf(id)
    }

    /** Duplicates of [id] go back to PENDING and into the current run: [id] no longer has passages to lend them. */
    private fun requeueDuplicatesOf(id: Long): List<Long> =
        books.requeueDuplicatesOf(listOf(id)).onEach(::enqueue)

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
            // a failed re-index (Ollama restarting, a converter timeout) leaves the previous passages, which are fine
            if ((books.find(row.book.id)?.book?.chunkCount ?: 0) > 0) books.markFailedKeepingPassages(row.book.id, msg)
            else empty(row.book.id, BookStatus.FAILED, msg)
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
        /** Fewest shared passages that make a book a duplicate (or all of a shorter one). */
        private const val MIN_SHARED = 5

        fun embeddingInput(title: String, chunk: Chunk): String = buildString {
            append("Book: ").append(title).append('\n')
            if (chunk.section != null) append("Section: ").append(chunk.section).append('\n')
            append('\n').append(chunk.text)
        }
    }
}
