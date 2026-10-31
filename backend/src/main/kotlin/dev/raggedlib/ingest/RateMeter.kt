package dev.raggedlib.ingest

import java.util.ArrayDeque

/** Rolling events/second over a time window. Thread-safe. */
class RateMeter(private val windowMillis: Long = 60_000, private val clock: () -> Long = System::currentTimeMillis) {
    private val events = ArrayDeque<Pair<Long, Int>>()
    private var startedAt = clock()

    @Synchronized
    fun reset() {
        events.clear()
        startedAt = clock()
    }

    @Synchronized
    fun record(n: Int) {
        events.addLast(clock() to n)
        prune(clock())
    }

    @Synchronized
    fun perSecond(): Double {
        val now = clock()
        prune(now)
        if (events.isEmpty()) return 0.0
        val total = events.sumOf { it.second }
        val span = (now - maxOf(startedAt, now - windowMillis)).coerceAtLeast(1_000)
        return total * 1000.0 / span
    }

    private fun prune(now: Long) {
        while (events.isNotEmpty() && events.first.first < now - windowMillis) events.removeFirst()
    }
}
