package dev.raggedlib.search

/** Reciprocal Rank Fusion + per-book diversity cap. Pure functions, unit-testable. */
object HybridRanking {

    /** [weight] in 0..1 scales this hit's contribution — how much the retriever vouches for it. */
    data class Candidate(val id: String, val bookId: Long, val weight: Double = 1.0)

    data class Fused(val id: String, val bookId: Long, val score: Double)

    /**
     * Weighted RRF. Each list is ordered best-first; score(d) = Σ over lists of weight(d) / (k + rank), rank 1-based.
     * With all weights 1 this is plain RRF.
     */
    fun rrf(lists: List<List<Candidate>>, k: Int): List<Fused> {
        val scores = LinkedHashMap<String, Double>()
        val books = HashMap<String, Long>()
        for (list in lists) {
            list.forEachIndexed { i, c ->
                val w = if (c.weight.isFinite()) c.weight.coerceAtLeast(0.0) else 0.0
                scores.merge(c.id, w / (k + i + 1), Double::plus)
                books[c.id] = c.bookId
            }
        }
        return scores.entries.sortedByDescending { it.value }.map { Fused(it.key, books.getValue(it.key), it.value) }
    }

    /**
     * Maps a cosine similarity to an RRF weight: 0 at or below [floor], 1 at or above [full], linear between.
     * Plain RRF only sees ranks, so the vector side's #1 counted as much as a full keyword match even when it was
     * a weak 0.5-similarity hit (the embedding misread the query). The bounds are embedding-model specific.
     */
    fun similarityWeight(similarity: Double, floor: Double, full: Double): Double = when {
        !similarity.isFinite() -> 0.0 // e.g. pgvector returns NaN distance for an all-zero vector
        full <= floor -> 1.0
        else -> ((similarity - floor) / (full - floor)).coerceIn(0.0, 1.0)
    }

    /**
     * Drops candidates nothing vouches for: non-positive scores, and scores below [minRelative] × the best one.
     * Must run before [capPerBook], whose diversity pass otherwise fills slots with near-zero hits from other
     * books ahead of strong hits from a capped book. Relative, so a query whose results are all weak still
     * gets its best ones.
     */
    fun dropWeak(fused: List<Fused>, minRelative: Double): List<Fused> {
        val top = fused.maxOfOrNull { it.score } ?: return fused
        if (top <= 0.0) return emptyList()
        val threshold = top * minRelative.coerceIn(0.0, 1.0)
        return fused.filter { it.score > 0.0 && it.score >= threshold }
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
