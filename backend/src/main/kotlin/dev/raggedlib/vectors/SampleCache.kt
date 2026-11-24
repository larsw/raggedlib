package dev.raggedlib.vectors

import dev.raggedlib.cancel.Cancellation
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
 * Identical requests arriving while one is being computed wait for it rather than computing it again. A request
 * that is cancelled ([Cancellation.current]) stops waiting, and once every request waiting for a computation has
 * been, the computation is cancelled too ([compute] runs with a [Cancellation] of its own, current): nobody wants it
 * (see [SharedComputation]).
 * At most [maxEntries] are kept, least recently used dropped first. All in memory: a restart starts over from seed 1.
 */
class SampleCache<Spec, T>(
    private val compute: (libraryId: Long, spec: Spec, seed: Long) -> T,
    private val markCached: (T) -> T,
    private val fingerprint: (libraryId: Long) -> String,
    private val maxEntries: Int = 16,
    /** Each library's current seed; caches of the same samples (layouts and topics) share it, see [currentSeed]. */
    private val currentSeeds: ConcurrentHashMap<Long, Long> = ConcurrentHashMap(),
) {
    private data class Key<Spec>(val libraryId: Long, val spec: Spec)

    private class Entry<T>(val seed: Long, val fingerprint: String) {
        val computation = SharedComputation<T>()
        val lastUsed = AtomicLong()
    }

    private val entries = ConcurrentHashMap<Key<Spec>, Entry<T>>()
    private val clock = AtomicLong()

    /** The seed of [libraryId]'s current sample: [DEFAULT_SEED] until it is reshuffled. */
    fun currentSeed(libraryId: Long): Long = currentSeeds[libraryId] ?: DEFAULT_SEED

    /** The result for [spec] of the library's current sample, or, given a [seed], of that sample (which becomes current). */
    fun get(libraryId: Long, spec: Spec, seed: Long? = null): T {
        if (seed != null) currentSeeds[libraryId] = seed
        val s = seed ?: currentSeed(libraryId)
        val fp = fingerprint(libraryId)
        val key = Key(libraryId, spec)
        val mine = Entry<T>(s, fp)
        val entry = entries.compute(key) { _, old ->
            if (old != null && old.seed == s && old.fingerprint == fp && old.computation.share()) old else mine
        }!!
        entry.lastUsed.set(clock.incrementAndGet())

        if (entry !== mine) return markCached(entry.computation.await())
        evictBeyondLimit()
        try {
            return entry.computation.run { compute(libraryId, spec, s) }
        } catch (e: Throwable) {
            entries.remove(key, entry) // the next request tries again
            throw e
        }
    }

    private fun evictBeyondLimit() {
        while (entries.size > maxEntries) {
            val oldest = entries.entries.filter { it.value.computation.isDone }.minByOrNull { it.value.lastUsed.get() } ?: return
            entries.remove(oldest.key, oldest.value)
        }
    }

    companion object {
        const val DEFAULT_SEED = 1L
    }
}
