package dev.raggedlib.vectors

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The sampled rows of a library, fetched and parsed once for every layout and topics run of that sample: switching
 * method, dimensions or a parameter, or clustering topics, computes from the rows already here instead of querying
 * again. [SampleCache] keeps those results; this keeps what they are computed from.
 *
 * A sample is the rows with the smallest md5(id, seed), in that order, so a smaller sample is the start of a larger
 * one with the same seed: per library, level and seed the largest fetched is kept, and smaller sizes are served from
 * it. Kept while the library's vectors are unchanged ([fingerprint], as [SampleCache] checks it); a reshuffle is a
 * new seed, so a new sample. At most [maxEntries] are kept (2,000 rows of 1,024-dimensional embeddings are about
 * 9 MB), least recently used dropped first.
 *
 * Requests for a sample being fetched wait for that fetch, if it is large enough, and the fetch is cancelled only
 * once every request waiting for it is (see [SharedComputation]).
 */
class SampleStore<R>(
    private val fingerprint: (libraryId: Long) -> String,
    private val maxEntries: Int = 4,
) {
    /**
     * The first [rows] of a sample, [asked] of them asked for (fewer: the library has no more), with the counts over
     * the library they were drawn from: [total] rows to draw from, and books without a book vector.
     */
    data class Sample<R>(val rows: List<R>, val asked: Int, val total: Long, val missingBookVectors: Int) {
        /** Holds the first [size] rows of the sample: as many were asked for, or the library has no more. */
        fun covers(size: Int): Boolean = size <= asked || rows.size < asked

        /** The first [size] rows, the sample of that size. */
        fun first(size: Int): Sample<R> = if (size >= rows.size) this else copy(rows = rows.subList(0, size), asked = size)
    }

    private data class Key(val libraryId: Long, val level: VectorLevel, val seed: Long)

    private class Entry<R>(val fingerprint: String, val asked: Int) {
        val fetch = SharedComputation<Sample<R>>()
        val lastUsed = AtomicLong()

        /** Holds the first [size] rows, or will once fetched. */
        fun covers(size: Int): Boolean = size <= asked || fetch.valueNow()?.covers(size) == true
    }

    private val entries = ConcurrentHashMap<Key, Entry<R>>()
    private val clock = AtomicLong()

    /**
     * The sample of [size] rows of [libraryId] at [level] with [seed]: from a sample kept or being fetched that holds
     * them, else from [fetch] (given the size to fetch, run under a cancellation of its own: see [SharedComputation]).
     */
    fun get(libraryId: Long, level: VectorLevel, seed: Long, size: Int, fetch: (size: Int) -> Sample<R>): Sample<R> {
        val fp = fingerprint(libraryId)
        val key = Key(libraryId, level, seed)
        val mine = Entry<R>(fp, size)
        // a smaller or outdated sample is replaced; a fetch of it already running goes on for those waiting for it
        val entry = entries.compute(key) { _, old ->
            if (old != null && old.fingerprint == fp && old.covers(size) && old.fetch.share()) old else mine
        }!!
        entry.lastUsed.set(clock.incrementAndGet())

        val sample = if (entry !== mine) {
            entry.fetch.await()
        } else {
            evictBeyondLimit()
            try {
                entry.fetch.run { fetch(size) }
            } catch (e: Throwable) {
                entries.remove(key, entry) // the next request fetches again
                throw e
            }
        }
        return sample.first(size)
    }

    /** Samples kept or being fetched (for tests). */
    internal fun size(): Int = entries.size

    private fun evictBeyondLimit() {
        while (entries.size > maxEntries) {
            val oldest = entries.entries.filter { it.value.fetch.isDone }.minByOrNull { it.value.lastUsed.get() } ?: return
            entries.remove(oldest.key, oldest.value)
        }
    }
}
