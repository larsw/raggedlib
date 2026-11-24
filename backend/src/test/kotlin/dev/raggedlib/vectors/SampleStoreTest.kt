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

class SampleStoreTest {
    private val P = VectorLevel.PASSAGES
    private val fetches = mutableListOf<String>()
    private val fingerprints = mutableMapOf(1L to "a", 2L to "b")
    /** How many rows each library has to sample from. */
    private val available = mutableMapOf(1L to 5000, 2L to 5000)

    private fun store(maxEntries: Int = 4) = SampleStore<String>({ fingerprints.getValue(it) }, maxEntries)

    /** The sample as the query gives it: the first [size] of the library's rows in its seed's order. */
    private fun SampleStore<String>.get(lib: Long, size: Int, seed: Long = 1, level: VectorLevel = P) =
        get(lib, level, seed, size) { n ->
            fetches += "$lib/${level.json()}/$seed/$n"
            val rows = List(minOf(n, available.getValue(lib))) { "$lib:$seed:$it" }
            SampleStore.Sample(rows, n, available.getValue(lib).toLong(), 0)
        }

    @Test
    fun `a smaller sample after a larger one is the start of it, not fetched again`() {
        val s = store()
        val large = s.get(1, 2000)
        val small = s.get(1, 500)
        assertEquals(large.rows.take(500), small.rows)
        assertEquals(500, small.asked)
        assertEquals(2000, s.get(1, 2000).rows.size)
        assertEquals(listOf("1/passages/1/2000"), fetches)
    }

    @Test
    fun `a larger sample is fetched, and then serves the smaller ones`() {
        val s = store()
        s.get(1, 500)
        s.get(1, 2000)
        s.get(1, 500)
        s.get(1, 1000)
        assertEquals(listOf("1/passages/1/500", "1/passages/1/2000"), fetches)
        assertEquals(1, s.size(), "the larger replaced the smaller")
    }

    @Test
    fun `a library with fewer rows than asked for has them all, for any size`() {
        available[1] = 120
        val s = store()
        assertEquals(120, s.get(1, 500).rows.size)
        assertEquals(120, s.get(1, 2000).rows.size)
        assertEquals(100, s.get(1, 100).rows.size)
        assertEquals(listOf("1/passages/1/500"), fetches)
    }

    @Test
    fun `another seed, level or library is another sample`() {
        val s = store()
        s.get(1, 1000)
        s.get(1, 1000, seed = 42)
        s.get(1, 1000, level = VectorLevel.BOOKS)
        s.get(2, 1000)
        s.get(1, 1000)
        assertEquals(listOf("1/passages/1/1000", "1/passages/42/1000", "1/books/1/1000", "2/passages/1/1000"), fetches)
    }

    @Test
    fun `a change to the library's vectors drops its samples only`() {
        val s = store()
        s.get(1, 1000)
        s.get(2, 1000)
        fingerprints[1] = "a2"
        s.get(1, 500)
        s.get(2, 500)
        assertEquals(listOf("1/passages/1/1000", "2/passages/1/1000", "1/passages/1/500"), fetches)
    }

    @Test
    fun `at most a few samples are kept, the least recently used dropped first`() {
        val s = store(maxEntries = 2)
        s.get(1, 1000, seed = 1)
        s.get(1, 1000, seed = 2)
        s.get(1, 1000, seed = 1) // seed 1 used last: seed 2 is the one to go
        s.get(1, 1000, seed = 3)
        assertEquals(2, s.size())
        s.get(1, 1000, seed = 1)
        s.get(1, 1000, seed = 2)
        assertEquals(listOf("1/passages/1/1000", "1/passages/2/1000", "1/passages/3/1000", "1/passages/2/1000"), fetches)
    }

    @Test
    fun `a failed fetch isn't kept`() {
        val s = store()
        assertFailsWith<IllegalStateException> { s.get(1, P, 1, 1000) { error("query failed") } }
        s.get(1, 1000)
        assertEquals(listOf("1/passages/1/1000"), fetches)
    }

    /** A store whose fetch runs until [release]d, or until it is cancelled. */
    private class Gate {
        val started = CountDownLatch(1)
        @Volatile private var released = false
        val fetches = AtomicInteger()
        val cancelled = AtomicInteger()
        val store = SampleStore<Int>({ "fp" })

        fun fetch(n: Int): SampleStore.Sample<Int> {
            fetches.incrementAndGet()
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
            return SampleStore.Sample(List(n) { it }, n, 10_000, 0)
        }

        fun release() {
            released = true
        }
    }

    /** A sample of [size] on a thread of its own, as a request (or a layout computation) with [cancellation]. */
    private class Request(gate: Gate, val size: Int = 1000, val cancellation: Cancellation = Cancellation()) {
        private val outcome = CompletableFuture<Result<List<Int>>>()
        private val thread = thread {
            outcome.complete(runCatching { cancellation.bind { gate.store.get(1, VectorLevel.PASSAGES, 1, size, gate::fetch).rows } })
        }

        /** Until it waits for another request's fetch. */
        fun awaitWaiting() {
            val deadline = System.nanoTime() + 5_000_000_000
            while (thread.state != Thread.State.WAITING) {
                check(System.nanoTime() < deadline) { "not waiting: ${thread.state}" }
                Thread.sleep(1)
            }
        }

        fun result(): List<Int> = outcome.get(10, TimeUnit.SECONDS).getOrThrow()
    }

    @Test
    fun `requests for a sample being fetched share the fetch, a smaller one too`() {
        val gate = Gate()
        val first = Request(gate, size = 2000)
        gate.started.await()
        val second = Request(gate, size = 500)
        second.awaitWaiting()
        gate.release()
        assertEquals(2000, first.result().size)
        assertEquals((0 until 500).toList(), second.result())
        assertEquals(1, gate.fetches.get())
    }

    @Test
    fun `a cancelled request stops a fetch nobody else waits for`() {
        val gate = Gate()
        val first = Request(gate)
        gate.started.await()
        first.cancellation.cancel()
        assertFailsWith<RequestCancelledException> { first.result() }
        assertEquals(1, gate.cancelled.get())

        gate.release()
        assertEquals(1000, Request(gate).result().size, "fetched again, not the cancelled one")
        assertEquals(2, gate.fetches.get())
    }

    @Test
    fun `a fetch others wait for goes on when the request that started it is cancelled`() {
        val gate = Gate()
        val first = Request(gate)
        gate.started.await()
        val second = Request(gate)
        second.awaitWaiting()

        first.cancellation.cancel()
        Thread.sleep(50)
        gate.release()
        assertEquals(1000, second.result().size)
        assertEquals(0, gate.cancelled.get())
        assertEquals(1, gate.fetches.get())
    }

    @Test
    fun `once every request waiting for a fetch is cancelled, so is the fetch`() {
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
    }

    @Test
    fun `a layout and topics computed from one sample count as two requests waiting for its fetch`() {
        // as in VectorSpaceService: each SampleCache computation fetches its sample under its own cancellation
        val gate = Gate()
        val layouts = SampleCache<String, Int>({ _, _, _ -> gate.store.get(1, VectorLevel.PASSAGES, 1, 1000, gate::fetch).rows.size }, { it }, { "fp" })
        val topics = SampleCache<String, Int>({ _, _, _ -> gate.store.get(1, VectorLevel.PASSAGES, 1, 1000, gate::fetch).rows.size }, { it }, { "fp" })
        val layoutRequest = Cancellation()
        val topicsRequest = Cancellation()
        val layout = CompletableFuture.supplyAsync { runCatching { layoutRequest.bind { layouts.get(1, "pca") } } }
        gate.started.await()
        val topic = CompletableFuture.supplyAsync { runCatching { topicsRequest.bind { topics.get(1, "raw") } } }
        Thread.sleep(100) // the topics run waits for the layout's fetch

        layoutRequest.cancel() // the layout is cancelled; the fetch goes on for the topics
        Thread.sleep(50)
        assertEquals(0, gate.cancelled.get())
        gate.release()
        assertEquals(1000, topic.get(10, TimeUnit.SECONDS).getOrThrow())
        // the layout's thread ran the fetch, for both; nothing after it checks for the cancel in this stand-in
        assertEquals(1000, layout.get(10, TimeUnit.SECONDS).getOrThrow())
        assertEquals(1, gate.fetches.get())
    }
}
