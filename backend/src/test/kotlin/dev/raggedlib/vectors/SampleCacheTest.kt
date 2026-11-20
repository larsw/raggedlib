package dev.raggedlib.vectors

import dev.raggedlib.cancel.Cancellation
import dev.raggedlib.cancel.RequestCancelledException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SampleCacheTest {
    private val P = VectorLevel.PASSAGES
    private val computed = mutableListOf<String>()
    private var fingerprints = mutableMapOf(1L to "a", 2L to "b")
    private var failNext = false

    private fun cache(maxEntries: Int = 16) = SampleCache<LayoutSpec, VectorSpace>(
        compute = { lib, spec, seed ->
            val (level, method, size, dims) = spec
            if (failNext) {
                failNext = false
                error("projection failed")
            }
            computed += (if (level == VectorLevel.BOOKS) "books:" else "") + (if (dims == 3) "3d:" else "") + "$lib/$method/$size/$seed"
            VectorSpace(lib, level, method, dims, seed, size, 0, 0, 0, null, spec.params, 0, emptyList())
        },
        markCached = { it.copy(cached = true) },
        fingerprint = { fingerprints.getValue(it) },
        maxEntries = maxEntries,
    )

    @Test
    fun `the same library, method and size is computed once`() {
        val c = cache()
        assertFalse(c.get(1, LayoutSpec(P, VectorMethod.UMAP, 1000)).cached)
        assertTrue(c.get(1, LayoutSpec(P, VectorMethod.UMAP, 1000)).cached)
        c.get(1, LayoutSpec(P, VectorMethod.PCA, 1000))
        c.get(1, LayoutSpec(P, VectorMethod.UMAP, 500))
        c.get(2, LayoutSpec(P, VectorMethod.UMAP, 1000))
        assertEquals(listOf("1/UMAP/1000/1", "1/PCA/1000/1", "1/UMAP/500/1", "2/UMAP/1000/1"), computed)
    }

    @Test
    fun `a change to the library's passages invalidates its layouts only`() {
        val c = cache()
        c.get(1, LayoutSpec(P, VectorMethod.UMAP, 1000))
        c.get(2, LayoutSpec(P, VectorMethod.UMAP, 1000))
        fingerprints[1] = "a2"
        assertFalse(c.get(1, LayoutSpec(P, VectorMethod.UMAP, 1000)).cached)
        assertTrue(c.get(2, LayoutSpec(P, VectorMethod.UMAP, 1000)).cached)
        assertEquals(3, computed.size)
    }

    @Test
    fun `a reshuffle makes the new sample the library's current one, for every method`() {
        val c = cache()
        c.get(1, LayoutSpec(P, VectorMethod.UMAP, 1000))
        c.get(1, LayoutSpec(P, VectorMethod.PCA, 1000))
        assertEquals(42, c.get(1, LayoutSpec(P, VectorMethod.UMAP, 1000), seed = 42).seed)
        // without a seed (a page load, another method): the reshuffled sample, not the old one
        assertTrue(c.get(1, LayoutSpec(P, VectorMethod.UMAP, 1000)).cached)
        assertEquals(42, c.get(1, LayoutSpec(P, VectorMethod.PCA, 1000)).seed)
        assertEquals(1, c.get(2, LayoutSpec(P, VectorMethod.PCA, 1000)).seed, "other libraries keep their sample")
        assertEquals(listOf("1/UMAP/1000/1", "1/PCA/1000/1", "1/UMAP/1000/42", "1/PCA/1000/42", "2/PCA/1000/1"), computed)
    }

    @Test
    fun `passages and books are cached apart, and share the library's sample`() {
        val c = cache()
        c.get(1, LayoutSpec(P, VectorMethod.UMAP, 1000))
        assertFalse(c.get(1, LayoutSpec(VectorLevel.BOOKS, VectorMethod.UMAP, 1000)).cached)
        assertTrue(c.get(1, LayoutSpec(VectorLevel.BOOKS, VectorMethod.UMAP, 1000)).cached)
        c.get(1, LayoutSpec(VectorLevel.BOOKS, VectorMethod.UMAP, 1000), seed = 9)
        assertEquals(9, c.get(1, LayoutSpec(P, VectorMethod.UMAP, 1000)).seed)
        assertEquals(listOf("1/UMAP/1000/1", "books:1/UMAP/1000/1", "books:1/UMAP/1000/9", "1/UMAP/1000/9"), computed)
    }

    @Test
    fun `2-D and 3-D layouts are cached apart`() {
        val c = cache()
        c.get(1, LayoutSpec(P, VectorMethod.UMAP, 1000))
        assertFalse(c.get(1, LayoutSpec(P, VectorMethod.UMAP, 1000, dims = 3)).cached)
        assertTrue(c.get(1, LayoutSpec(P, VectorMethod.UMAP, 1000, dims = 3)).cached)
        assertEquals(listOf("1/UMAP/1000/1", "3d:1/UMAP/1000/1"), computed)
    }

    @Test
    fun `different parameters are cached apart`() {
        val c = cache()
        val umap = LayoutSpec(P, VectorMethod.UMAP, 1000)
        c.get(1, umap)
        val tighter = umap.copy(params = umap.params.copy(minDist = 0.0))
        assertFalse(c.get(1, tighter).cached)
        assertTrue(c.get(1, tighter).cached)
        assertTrue(c.get(1, umap).cached)
    }

    @Test
    fun `a failure isn't cached`() {
        val c = cache()
        failNext = true
        assertFailsWith<IllegalStateException> { c.get(1, LayoutSpec(P, VectorMethod.TSNE, 250)) }
        assertFalse(c.get(1, LayoutSpec(P, VectorMethod.TSNE, 250)).cached)
    }

    /** A cache whose computation runs until [release]d, or until it is cancelled. */
    private class Gate {
        val started = CountDownLatch(1)
        @Volatile private var released = false
        val computations = AtomicInteger()
        val cancelled = AtomicInteger()
        val cache = SampleCache<String, String>(
            compute = { _, spec, _ ->
                computations.incrementAndGet()
                started.countDown()
                val deadline = System.nanoTime() + 10_000_000_000
                try {
                    while (!released) {
                        Cancellation.current().throwIfCancelled()
                        check(System.nanoTime() < deadline) { "neither released nor cancelled" }
                        Thread.sleep(1)
                    }
                } catch (e: RequestCancelledException) {
                    cancelled.incrementAndGet()
                    throw e
                }
                spec
            },
            markCached = { "$it (cached)" },
            fingerprint = { "fp" },
        )

        fun release() {
            released = true
        }
    }

    /** [cache].get for [spec] on a thread of its own, as a request with [cancellation]. */
    private class Request(gate: Gate, val cancellation: Cancellation = Cancellation(), spec: String = "umap") {
        // a Result: a future completed with a CancellationException would report one of its own instead
        private val outcome = CompletableFuture<Result<String>>()
        private val thread = thread {
            outcome.complete(runCatching { cancellation.bind { gate.cache.get(1, spec) } })
        }

        /** Until it waits for another request's computation. */
        fun awaitWaiting() {
            val deadline = System.nanoTime() + 5_000_000_000
            while (thread.state != Thread.State.WAITING) {
                check(System.nanoTime() < deadline) { "not waiting: ${thread.state}" }
                Thread.sleep(1)
            }
        }

        fun result(): String = outcome.get(10, TimeUnit.SECONDS).getOrThrow()
    }

    @Test
    fun `a request cancelled while computing stops its computation, and the next one computes afresh`() {
        val gate = Gate()
        val first = Request(gate)
        gate.started.await()
        first.cancellation.cancel()
        assertFailsWith<RequestCancelledException> { first.result() }
        assertEquals(1, gate.cancelled.get())

        gate.release()
        assertEquals("umap", Request(gate).result(), "computed again, not the cancelled one")
        assertEquals(2, gate.computations.get())
    }

    @Test
    fun `a computation goes on while a request still waits for it`() {
        val gate = Gate()
        val first = Request(gate)
        gate.started.await()
        val second = Request(gate)
        second.awaitWaiting()

        first.cancellation.cancel() // the one computing: it goes on, for the other
        Thread.sleep(50)
        gate.release()
        assertEquals("umap (cached)", second.result())
        assertEquals(0, gate.cancelled.get())
        assertEquals(1, gate.computations.get())
    }

    @Test
    fun `a request cancelled while waiting stops waiting, and the computation goes on for the others`() {
        val gate = Gate()
        val first = Request(gate)
        gate.started.await()
        val second = Request(gate)
        second.awaitWaiting()

        second.cancellation.cancel()
        assertFailsWith<RequestCancelledException> { second.result() }
        gate.release()
        assertEquals("umap", first.result())
        assertEquals(0, gate.cancelled.get())
    }

    @Test
    fun `once every request waiting for a computation is cancelled, so is the computation`() {
        val gate = Gate()
        val first = Request(gate)
        gate.started.await()
        val second = Request(gate)
        second.awaitWaiting()

        second.cancellation.cancel()
        first.cancellation.cancel()
        assertFailsWith<RequestCancelledException> { first.result() }
        assertFailsWith<RequestCancelledException> { second.result() }
        assertEquals(1, gate.cancelled.get())

        // a request arriving after that computes it again rather than waiting for the cancelled one
        gate.release()
        assertEquals("umap", Request(gate).result())
        assertEquals(2, gate.computations.get())
    }

    @Test
    fun `a request already cancelled computes nothing`() {
        val gate = Gate()
        gate.release()
        val request = Request(gate, Cancellation().apply { cancel() })
        assertFailsWith<RequestCancelledException> { request.result() }
        assertEquals("umap", Request(gate).result())
    }

    @Test
    fun `beyond the limit, the least recently used layout goes first`() {
        val c = cache(maxEntries = 2)
        c.get(1, LayoutSpec(P, VectorMethod.PCA, 250))
        c.get(1, LayoutSpec(P, VectorMethod.UMAP, 250))
        c.get(1, LayoutSpec(P, VectorMethod.PCA, 250)) // PCA used last: UMAP is the one to go
        c.get(1, LayoutSpec(P, VectorMethod.TSNE, 250))
        assertTrue(c.get(1, LayoutSpec(P, VectorMethod.PCA, 250)).cached)
        assertFalse(c.get(1, LayoutSpec(P, VectorMethod.UMAP, 250)).cached)
    }
}
