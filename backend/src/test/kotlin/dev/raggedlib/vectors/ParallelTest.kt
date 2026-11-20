package dev.raggedlib.vectors

import dev.raggedlib.cancel.Cancellation
import dev.raggedlib.cancel.RequestCancelledException
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParallelTest {
    @Test
    fun `forEach visits every index once`() {
        for (n in listOf(0, 1, 63, 64, 65, 1000)) {
            val hits = IntArray(n)
            Parallel.forEach(n) { synchronized(hits) { hits[it]++ } }
            assertTrue(hits.all { it == 1 }, "n=$n")
        }
    }

    @Test
    fun `chunks cover the range in order`() {
        val chunks = Parallel.chunked(1000) { from, to -> from until to }
        assertEquals(0, chunks.first().first)
        assertEquals(999, chunks.last().last)
        chunks.zipWithNext { a, b -> assertEquals(a.last + 1, b.first) }
        // fewer items than chunks: one per chunk
        assertEquals(listOf(0 until 1, 1 until 2, 2 until 3), Parallel.chunked(3) { from, to -> from until to })
    }

    @Test
    fun `sum adds up the same way every time`() {
        // terms whose sum depends on the order they're added in
        val term = { i: Int -> if (i % 2 == 0) 1e16 / (i + 1) else 1.0 / (i + 1) }
        val first = Parallel.sum(5000, term)
        repeat(20) { assertEquals(first, Parallel.sum(5000, term)) }
        assertEquals((0 until 5000).sumOf(term), first, 1e-6 * first)
    }

    @Test
    fun `a failure in a chunk reaches the caller as itself`() {
        val e = assertFailsWith<IllegalStateException> { Parallel.forEach(1000) { if (it == 777) error("boom") } }
        assertEquals("boom", e.message)
    }

    @Test
    fun `layouts are the same from one run to the next`() {
        val rnd = kotlin.random.Random(3)
        val rows = Array(300) { FloatArray(16) { rnd.nextFloat() - 0.5f } }
        val x = Array(rows.size) { i -> DoubleArray(16) { rows[i][it].toDouble() } }
        fun layout(): List<Array<DoubleArray>> {
            val init = Projections.pca(x, seed = 1).coords
            val sq = Projections.squaredCosineDistances(rows)
            return listOf(
                init,
                Projections.tsne(sq, rows.size, init, perplexity = 20.0, iterations = 300),
                Projections.umap(sq, rows.size, init, epochs = 50, seed = 1),
            )
        }
        val a = layout()
        val b = layout()
        for (m in a.indices) for (i in a[m].indices) assertTrue(a[m][i].contentEquals(b[m][i]), "method $m point $i")
    }

    @Test
    fun `a cancelled computation does no more chunks, on the pool's threads too`() {
        val cancelled = Cancellation().apply { cancel() }
        var visited = 0
        assertFailsWith<RequestCancelledException> { cancelled.bind { Parallel.forEach(1000) { visited++ } } }
        assertFailsWith<RequestCancelledException> { cancelled.bind { Parallel.forEach(10) { visited++ } } }
        assertEquals(0, visited)

        // cancelled during the first chunk: the chunks no thread has started yet don't run
        assumeTrue(Runtime.getRuntime().availableProcessors() < 64, "every chunk would start at once")
        val cancellation = Cancellation()
        val done = AtomicInteger()
        assertFailsWith<RequestCancelledException> {
            cancellation.bind {
                Parallel.forEach(6400) {
                    if (it == 0) cancellation.cancel()
                    Thread.sleep(0, 100_000)
                    done.incrementAndGet()
                }
            }
        }
        assertTrue(done.get() < 6400, "${done.get()} done")
    }
}
