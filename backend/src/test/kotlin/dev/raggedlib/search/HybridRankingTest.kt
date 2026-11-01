package dev.raggedlib.search

import dev.raggedlib.search.HybridRanking.Candidate
import kotlin.test.Test
import kotlin.test.assertEquals

class HybridRankingTest {
    @Test
    fun `rrf rewards documents found by both retrievers`() {
        val vector = listOf(Candidate("a", 1), Candidate("b", 1), Candidate("c", 2))
        val text = listOf(Candidate("c", 2), Candidate("d", 3))
        val fused = HybridRanking.rrf(listOf(vector, text), 60)
        assertEquals("c", fused.first().id)
        assertEquals(1.0 / 63 + 1.0 / 61, fused.first().score, 1e-12)
        assertEquals(listOf("c", "a", "b", "d"), fused.map { it.id })
    }

    @Test
    fun `weighted rrf lets a confident keyword hit beat a weak vector rank 1`() {
        // "return oriented programming gadgets": vector #1 is an OOP passage at similarity 0.51,
        // the keyword side's #1 is the actual ROP chapter
        val weak = HybridRanking.similarityWeight(0.514, 0.40, 0.70)
        val vector = listOf(Candidate("oop", 1, weak), Candidate("uml", 2, weak))
        val keyword = listOf(Candidate("rop", 3, 1.0))
        assertEquals("rop", HybridRanking.rrf(listOf(vector, keyword), 60).first().id)
        // with plain RRF the two rank-1 hits tie and list order decides
        val plain = HybridRanking.rrf(listOf(vector.map { it.copy(weight = 1.0) }, keyword), 60)
        assertEquals(plain[0].score, plain[1].score, 1e-12)
    }

    @Test
    fun `weak candidates are dropped before the per-book cap`() {
        // "puteanus": one book has many strong hits, other books only near-zero vector noise
        val strong = (1..8).map { HybridRanking.Fused("taocp$it", 667, 0.0159) }
        val noise = (1..5).map { HybridRanking.Fused("noise$it", 100L + it, 0.0005) } + HybridRanking.Fused("zero", 200, 0.0)
        val kept = HybridRanking.dropWeak(strong + noise, 0.10)
        assertEquals(8, kept.size)
        assertEquals(8, HybridRanking.capPerBook(kept, 3, 8).count { it.bookId == 667L }) // backfilled from the capped book
        // all-weak results are kept relative to their own best; all-zero means nothing
        assertEquals(5, HybridRanking.dropWeak(noise.dropLast(1), 0.10).size)
        assertEquals(0, HybridRanking.dropWeak(listOf(HybridRanking.Fused("z", 1, 0.0)), 0.10).size)
    }

    @Test
    fun `non-finite weights count as zero`() {
        assertEquals(0.0, HybridRanking.similarityWeight(Double.NaN, 0.40, 0.70))
        val fused = HybridRanking.rrf(listOf(listOf(Candidate("nan", 1, Double.NaN), Candidate("ok", 2, 0.5))), 60)
        assertEquals("ok", fused.first().id)
    }

    @Test
    fun `similarity weight is clamped and linear`() {
        assertEquals(0.0, HybridRanking.similarityWeight(0.35, 0.40, 0.70))
        assertEquals(0.5, HybridRanking.similarityWeight(0.55, 0.40, 0.70), 1e-9)
        assertEquals(1.0, HybridRanking.similarityWeight(0.82, 0.40, 0.70))
        assertEquals(1.0, HybridRanking.similarityWeight(0.10, 0.0, 0.0)) // disabled -> plain RRF
    }

    @Test
    fun `cap limits chunks per book`() {
        val fused = (1..6).map { HybridRanking.Fused("x$it", 1, 1.0 / it) } + HybridRanking.Fused("y", 2, 0.01)
        assertEquals(listOf("x1", "x2", "x3", "y"), HybridRanking.capPerBook(fused, 3, 4).map { it.id })
        // fewer distinct books than needed -> backfill by score
        assertEquals(listOf("x1", "x2", "x3", "x4", "y"), HybridRanking.capPerBook(fused, 3, 5).map { it.id })
        assertEquals(5, HybridRanking.capPerBook(fused, null, 5).size)
    }

    @Test
    fun `rerank reorders by reranker score, applies the cutoff and keeps fused order on ties`() {
        val fused = listOf("a" to 1L, "b" to 2L, "c" to 3L, "d" to 4L, "e" to 5L)
            .mapIndexed { i, (id, book) -> HybridRanking.Fused(id, book, 1.0 / (61 + i)) }
        val scores = doubleArrayOf(0.30, 0.95, 0.05, Double.NaN, 0.30)
        val out = HybridRanking.rerank(fused, scores, 0.10)
        assertEquals(listOf("b", "a", "e"), out.map { it.id })   // c below cutoff, d not finite; a before e (tie)
        assertEquals(listOf(0.95, 0.30, 0.30), out.map { it.score })
        assertEquals(emptyList(), HybridRanking.rerank(fused, DoubleArray(5) { 0.01 }, 0.10))
    }
}
