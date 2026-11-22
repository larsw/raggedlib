package dev.raggedlib.vectors

import dev.raggedlib.cancel.Cancellation
import dev.raggedlib.cancel.RequestCancelledException
import java.util.concurrent.CancellationException
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
 * Identical requests arriving while one is being computed wait for it rather than computing it again. A request
 * that is cancelled ([Cancellation.current]) stops waiting, and once every request waiting for a computation has
 * been, the computation is cancelled too ([compute] runs with a [Cancellation] of its own, current): nobody wants it.
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
        val result = CompletableFuture<T>()
        val lastUsed = AtomicLong()

        /** The computation's own, cancelled when the last request waiting for it is. */
        val cancellation = Cancellation()

        // guarded by this: requests waiting for the result, the one computing it included
        private var waiting = 1
        private var abandoned = false

        /** Whether a request can have this: done, or still being computed (then it waits for it too, see [leave]). */
        @Synchronized
        fun share(): Boolean = when {
            result.isCompletedExceptionally -> false
            result.isDone -> true
            abandoned -> false
            else -> {
                waiting++
                true
            }
        }

        /** A request waiting for it was cancelled; the last one to go cancels the computation. */
        fun leave() {
            val last = synchronized(this) {
                waiting--
                (waiting == 0 && !result.isDone).also { if (it) abandoned = true }
            }
            if (last) cancellation.cancel()
        }
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
            if (old != null && old.seed == s && old.fingerprint == fp && old.share()) old else mine
        }!!
        entry.lastUsed.set(clock.incrementAndGet())

        if (entry !== mine) return markCached(await(entry))
        evictBeyondLimit()
        // computed here for every request that comes to wait for it too, so under its own cancellation, not this
        // request's: this request being cancelled only means one fewer waiting
        Cancellation.current().onCancel(entry::leave).use {
            try {
                return entry.cancellation.bind {
                    entry.cancellation.throwIfCancelled() // this request already was, and no other waits yet
                    compute(libraryId, spec, s)
                }.also { entry.result.complete(it) }
            } catch (e: Throwable) {
                entry.result.completeExceptionally(e) // whoever waits on it gets the error too; the next request tries again
                entries.remove(key, entry)
                throw e
            }
        }
    }

    /** [entry]'s result, once computed; given up on with [RequestCancelledException] if this request is cancelled. */
    private fun await(entry: Entry<T>): T {
        // a copy to stop waiting on, without stopping the computation itself for the others
        val result = entry.result.copy()
        val registration = Cancellation.current().onCancel {
            if (result.cancel(false)) entry.leave()
        }
        registration.use {
            try {
                return result.join()
            } catch (_: CancellationException) {
                throw RequestCancelledException() // the copy was cancelled, by this request (a failure comes as below)
            } catch (e: CompletionException) {
                throw e.cause ?: e
            }
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
