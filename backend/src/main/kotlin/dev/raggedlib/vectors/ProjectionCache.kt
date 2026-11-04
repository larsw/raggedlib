package dev.raggedlib.vectors

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Projections take seconds for a large library, so the last one per (library, level, method, size) is kept and served
 * again for as long as it is still right:
 *
 * - the library's vectors haven't changed: [fingerprint] (cheap, from the books table) is what it was, and
 * - it is of the library's current sample: a reshuffle (a request with a new seed) makes that seed the library's
 *   current one, so every level, method and size of it is recomputed, from the new sample, the next time it's
 *   asked for.
 *
 * Identical requests arriving while one is being computed wait for it rather than computing it again. At most
 * [maxEntries] are kept, least recently used dropped first. All in memory: a restart starts over from seed 1.
 */
class ProjectionCache(
    private val compute: (libraryId: Long, level: VectorLevel, method: VectorMethod, size: Int, seed: Long) -> VectorSpace,
    private val fingerprint: (libraryId: Long) -> String,
    private val maxEntries: Int = 16,
) {
    private data class Key(val libraryId: Long, val level: VectorLevel, val method: VectorMethod, val size: Int)

    private class Entry(val seed: Long, val fingerprint: String, val result: CompletableFuture<VectorSpace>) {
        val lastUsed = AtomicLong()
    }

    private val entries = ConcurrentHashMap<Key, Entry>()
    private val currentSeeds = ConcurrentHashMap<Long, Long>()
    private val clock = AtomicLong()

    /** The seed of [libraryId]'s current sample: [DEFAULT_SEED] until it is reshuffled. */
    fun currentSeed(libraryId: Long): Long = currentSeeds[libraryId] ?: DEFAULT_SEED

    /** The layout of the library's current sample, or, given a [seed], of that sample (which becomes the current one). */
    fun get(libraryId: Long, level: VectorLevel, method: VectorMethod, size: Int, seed: Long? = null): VectorSpace {
        if (seed != null) currentSeeds[libraryId] = seed
        val s = seed ?: currentSeed(libraryId)
        val fp = fingerprint(libraryId)
        val key = Key(libraryId, level, method, size)
        val mine = CompletableFuture<VectorSpace>()
        val entry = entries.compute(key) { _, old ->
            if (old != null && old.seed == s && old.fingerprint == fp && !old.result.isCompletedExceptionally) old
            else Entry(s, fp, mine)
        }!!
        entry.lastUsed.set(clock.incrementAndGet())

        if (entry.result !== mine) {
            val result = try {
                entry.result.join()
            } catch (e: CompletionException) {
                throw e.cause ?: e
            }
            return result.copy(cached = true)
        }
        evictBeyondLimit()
        try {
            return compute(libraryId, level, method, size, s).also { mine.complete(it) }
        } catch (e: Throwable) {
            mine.completeExceptionally(e) // whoever waits on it gets the error too; the next request tries again
            entries.remove(key, entry)
            throw e
        }
    }

    private fun evictBeyondLimit() {
        while (entries.size > maxEntries) {
            val oldest = entries.entries.filter { it.value.result.isDone }.minByOrNull { it.value.lastUsed.get() } ?: return
            entries.remove(oldest.key, oldest.value)
        }
    }

    companion object {
        const val DEFAULT_SEED = 1L
    }
}
