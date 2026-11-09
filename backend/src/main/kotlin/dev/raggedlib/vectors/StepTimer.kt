package dev.raggedlib.vectors

/** Wall-clock time per named step of one computation, in the order they ran (a repeated name adds up). */
internal class StepTimer {
    private val started = System.nanoTime()
    private val ms = LinkedHashMap<String, Long>()

    /** Runs [block], adding its duration to [name]. */
    fun <T> step(name: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            ms.merge(name, (System.nanoTime() - t0) / 1_000_000, Long::plus)
        }
    }

    /** A copy of the steps so far, in order. */
    val steps: Map<String, Long> get() = LinkedHashMap(ms)

    fun elapsedMs(): Long = (System.nanoTime() - started) / 1_000_000

    /** "sample 812 ms, parse 95 ms, …" for the log. */
    fun summary(): String = ms.entries.joinToString(", ") { (name, t) -> "$name $t ms" }
}
