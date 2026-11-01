package dev.raggedlib.search

/**
 * Collapses search results that are (nearly) the same passage: one book printed twice in its PDF (an
 * introduction repeated), volumes or proceedings sharing front matter, two editions of a text. Exact copies across
 * whole books are caught at indexing ([dev.raggedlib.ingest.Duplicates]); these differ in chunk boundaries or a
 * few words, so they are compared by shared word 5-grams.
 */
object NearDuplicates {
    private const val N = 5
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** Hashed word [N]-grams of [text], case-insensitive; a text shorter than [N] words is one gram. */
    fun shingles(text: String): Set<Int> {
        val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
        if (words.size < N) return if (words.isEmpty()) emptySet() else setOf(words.joinToString(" ").hashCode())
        return (0..words.size - N).mapTo(HashSet()) { i -> words.subList(i, i + N).joinToString(" ").hashCode() }
    }

    /** Share of the smaller set that is in the other: 1 when one passage is contained in the other. */
    fun containment(a: Set<Int>, b: Set<Int>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (small, large) = if (a.size <= b.size) a to b else b to a
        return small.count { it in large }.toDouble() / small.size
    }

    /** [items] (best first) without those whose text repeats an earlier kept one by at least [threshold]. */
    fun <T> dropRepeats(items: List<T>, threshold: Double, text: (T) -> String): List<T> {
        if (threshold <= 0.0 || threshold > 1.0 || items.size < 2) return items
        val kept = ArrayList<Pair<T, Set<Int>>>(items.size)
        for (item in items) {
            val s = shingles(text(item))
            if (kept.none { containment(it.second, s) >= threshold }) kept += item to s
        }
        return kept.map { it.first }
    }
}
