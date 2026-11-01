package dev.raggedlib.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NearDuplicatesTest {
    private val intro = "An important part of the process is learning to compare and contrast with known solutions. " +
        "This book is not really about teaching native or managed code programming, either, although it uses " +
        "code samples to illustrate the debugging techniques it describes in the chapters that follow."

    @Test
    fun `the same passage with other boundaries is dropped, the better ranked one kept`() {
        val a = "Thanks in advance for your input! Introduction xxvii. $intro"
        val b = "$intro Finally, you will find two short appendices."
        val other = "Kernel memory leaks are investigated with pool tagging, which records the owner of each allocation."
        assertEquals(listOf("a", "other"), NearDuplicates.dropRepeats(listOf("a", "b", "other"), 0.6) {
            mapOf("a" to a, "b" to b, "other" to other).getValue(it)
        })
    }

    @Test
    fun `neighbouring chunks sharing their overlap are both kept`() {
        val words = (1..400).map { "w$it" }
        val first = words.subList(0, 250).joinToString(" ")
        val second = words.subList(215, 400).joinToString(" ") // ~35 words of overlap
        assertTrue(NearDuplicates.containment(NearDuplicates.shingles(first), NearDuplicates.shingles(second)) < 0.3)
        assertEquals(2, NearDuplicates.dropRepeats(listOf(first, second), 0.6) { it }.size)
    }

    @Test
    fun `case and punctuation don't matter, a threshold of 0 keeps everything`() {
        assertEquals(1.0, NearDuplicates.containment(NearDuplicates.shingles(intro), NearDuplicates.shingles(intro.uppercase().replace(",", ""))))
        assertEquals(2, NearDuplicates.dropRepeats(listOf(intro, intro), 0.0) { it }.size)
    }
}
