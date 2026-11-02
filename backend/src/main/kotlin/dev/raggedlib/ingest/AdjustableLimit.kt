package dev.raggedlib.ingest

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A semaphore whose size is read from [limit] each time a permit is wanted, so it can change while in use: raising
 * it lets waiting callers in at once (after [refresh]), lowering it lets the holders finish and admits nobody new
 * until fewer than the new limit are left.
 */
class AdjustableLimit(private val limit: () -> Int) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var inUse = 0

    fun acquire() = lock.withLock {
        // also wakes up now and then, so a raised limit is seen without a refresh() call
        while (inUse >= limit().coerceAtLeast(1)) changed.await(1, TimeUnit.SECONDS)
        inUse++
    }

    fun release() = lock.withLock {
        inUse--
        changed.signalAll()
    }

    /** Re-check the limit now (after it changed). */
    fun refresh() = lock.withLock { changed.signalAll() }

    val used: Int get() = lock.withLock { inUse }
}
