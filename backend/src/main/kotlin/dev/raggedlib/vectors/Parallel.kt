package dev.raggedlib.vectors

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spreads the vector space's CPU-bound loops (the O(n²) steps of [Projections] and [Hdbscan]) over the cores.
 *
 * Work is split into [CHUNKS] contiguous chunks of `0 until n` whatever the core count, and [chunked] returns their
 * results in chunk order, so a reduction adds up in the same order on every machine: a seed gives the same layout
 * on 4 cores as on 20. More chunks than cores also balances triangular loops (row i doing n - i pairs).
 *
 * Its own fixed pool (a plain one: a ForkJoinPool hands a task's exception back as a copy), not the common one, so a
 * large layout doesn't starve whatever else in the app uses that. Bodies must not call [Parallel] themselves: a
 * chunk waiting on chunks queued behind it in the same pool could wait forever.
 */
internal object Parallel {
    private const val CHUNKS = 64

    /** below this, the loop runs on the calling thread: forking costs more than it saves */
    private const val MIN_PARALLEL = 64

    private val threadIds = AtomicInteger()
    private val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()) { task ->
        Thread(task, "vectors-${threadIds.incrementAndGet()}").apply { isDaemon = true }
    }

    /** Runs [body] for every i in `0 until n`. Each i must touch only its own outputs. */
    fun forEach(n: Int, body: (Int) -> Unit) {
        if (n < MIN_PARALLEL) {
            for (i in 0 until n) body(i)
            return
        }
        chunked(n) { from, to -> for (i in from until to) body(i) }
    }

    /** Σ [term] over `0 until n`, summed per chunk and then in chunk order (see [Parallel]). */
    fun sum(n: Int, term: (Int) -> Double): Double = chunked(n) { from, to ->
        var s = 0.0
        for (i in from until to) s += term(i)
        s
    }.sum()

    /** [block] over each chunk `from until to` of `0 until n`; the results in chunk order. */
    fun <T> chunked(n: Int, block: (from: Int, to: Int) -> T): List<T> {
        val chunks = minOf(CHUNKS, n)
        if (chunks <= 1) return if (n <= 0) emptyList() else listOf(block(0, n))
        val tasks = List(chunks) { c ->
            val from = (n.toLong() * c / chunks).toInt()
            val to = (n.toLong() * (c + 1) / chunks).toInt()
            Callable { block(from, to) }
        }
        return pool.invokeAll(tasks).map { future ->
            try {
                future.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }
}
