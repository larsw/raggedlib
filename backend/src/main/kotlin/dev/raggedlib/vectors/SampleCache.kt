package dev.raggedlib.vectors

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Projections and topics both take seconds for a large library, so the last one per library and [Spec] is kept
 * and served again for as long as it is still right:
 *
 * - the library's vectors haven't changed: [fingerprint] (cheap, from the books table) is what it was, and
 * - it is of the library's current sample: a reshuffle (a request with a new seed) makes that seed the library's
 *   current one, so every [Spec] of it is recomputed, from the new sample, the next time it's asked for.
 *
 * Identical requests arriving while one is being computed wait for it rather than computing it again. At most
 * [maxEntries] are kept, least recently used dropped first. All in memory: a restart starts over from seed 1.
 */
class SampleCache<Spec, T>(
    private val compute: (libraryId: Long, spec: Spec, seed: Long) -> T,
    private val markCached: (T) -> T,
    private val fingerprint: (libraryId: Long) -> String,
    private val maxEntries: Int = 16,
) {
    private data class Key<Spec>(val libraryId: Long, val spec: Spec)

    private class Entry<T>(val seed: Long, val fingerprint: String, val result: CompletableFuture<T>) {
        val lastUsed = AtomicLong()
    }

    private val entries = ConcurrentHashMap<Key<Spec>, Entry<T>>()
    private val currentSeeds = ConcurrentHashMap<Long, Long>()
    private val clock = AtomicLong()

    /** The seed of [libraryId]'s current sample: [DEFAULT_SEED] until it is reshuffled. */
    fun currentSeed(libraryId: Long): Long = currentSeeds[libraryId] ?: DEFAULT_SEED

    /** The result for [spec] of the library's current sample, or, given a [seed], of that sample (which becomes current). */
    fun get(libraryId: Long, spec: Spec, seed: Long? = null): T {
        if (seed != null) currentSeeds[libraryId] = seed
        val s = seed ?: currentSeed(libraryId)
        val fp = fingerprint(libraryId)
        val key = Key(libraryId, spec)
        val mine = CompletableFuture<T>()
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
            return markCached(result)
        }
        evictBeyondLimit()
        try {
            return compute(libraryId, spec, s).also { mine.complete(it) }
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
