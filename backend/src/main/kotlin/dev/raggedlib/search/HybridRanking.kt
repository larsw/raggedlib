package dev.raggedlib.search

/** Reciprocal Rank Fusion + per-book diversity cap. Pure functions, unit-testable. */
object HybridRanking {

    data class Candidate(val id: String, val bookId: Long)

    data class Fused(val id: String, val bookId: Long, val score: Double)

    /** Each list is ordered best-first. score(d) = sum over lists of 1 / (k + rank), rank 1-based. */
    fun rrf(lists: List<List<Candidate>>, k: Int): List<Fused> {
        val scores = LinkedHashMap<String, Double>()
        val books = HashMap<String, Long>()
        for (list in lists) {
            list.forEachIndexed { i, c ->
                scores.merge(c.id, 1.0 / (k + i + 1), Double::plus)
                books[c.id] = c.bookId
            }
        }
        return scores.entries.sortedByDescending { it.value }.map { Fused(it.key, books.getValue(it.key), it.value) }
    }

    fun capPerBook(fused: List<Fused>, maxPerBook: Int?, topK: Int): List<Fused> {
        if (maxPerBook == null) return fused.take(topK)
        val perBook = HashMap<Long, Int>()
        val out = ArrayList<Fused>(topK)
        for (f in fused) {
            val n = perBook.getOrDefault(f.bookId, 0)
            if (n >= maxPerBook) continue
            perBook[f.bookId] = n + 1
            out += f
            if (out.size == topK) break
        }
        if (out.size < topK) {
            // not enough distinct books among the candidates: backfill with the best remaining chunks
            val taken = out.mapTo(HashSet()) { it.id }
            out += fused.filter { it.id !in taken }.take(topK - out.size)
            out.sortByDescending { it.score }
        }
        return out
    }
}
