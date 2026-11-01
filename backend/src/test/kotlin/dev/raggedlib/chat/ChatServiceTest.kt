package dev.raggedlib.chat

import dev.raggedlib.search.SourceChunk
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatServiceTest {
    private fun chunk(ref: Int, chars: Int) = SourceChunk(ref, "c$ref", 1, "t", emptyList(), null, null, "x".repeat(chars), 1.0)

    @Test
    fun `excerpts are cut to the context budget as a ranked prefix`() {
        val s = listOf(chunk(1, 6000), chunk(2, 6000), chunk(3, 6000), chunk(4, 100))
        // 3 x (6000 + overhead) exceeds 16000: keep 1 and 2, and not the small 4th that would still fit
        assertEquals(listOf(1, 2), ChatService.withinBudget(s, 16_000).map { it.ref })
        assertEquals(listOf(1, 2, 3, 4), ChatService.withinBudget(s, 100_000).map { it.ref })
    }

    @Test
    fun `the best passage is always kept, even when it alone is over budget`() {
        assertEquals(listOf(1), ChatService.withinBudget(listOf(chunk(1, 50_000), chunk(2, 10)), 16_000).map { it.ref })
        assertEquals(emptyList(), ChatService.withinBudget(emptyList(), 16_000))
    }
}
