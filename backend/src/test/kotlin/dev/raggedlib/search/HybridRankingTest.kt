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
    fun `cap limits chunks per book`() {
        val fused = (1..6).map { HybridRanking.Fused("x$it", 1, 1.0 / it) } + HybridRanking.Fused("y", 2, 0.01)
        assertEquals(listOf("x1", "x2", "x3", "y"), HybridRanking.capPerBook(fused, 3, 4).map { it.id })
        // fewer distinct books than needed -> backfill by score
        assertEquals(listOf("x1", "x2", "x3", "x4", "y"), HybridRanking.capPerBook(fused, 3, 5).map { it.id })
        assertEquals(5, HybridRanking.capPerBook(fused, null, 5).size)
    }
}
