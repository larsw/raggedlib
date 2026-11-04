package dev.raggedlib.vectors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TopicLabelsTest {
    @Test
    fun `distinctive terms outrank a word common to every cluster`() {
        // "common" isn't a stopword (those never reach TopicLabels — content_tsv already filters them) but it's a
        // generic content word that happens to occur about as often in both clusters; the distinctive terms should
        // still win despite a somewhat higher raw count, which real c-TF-IDF (unlike plain TF) is built to handle.
        val wordCounts = mapOf(
            0 to mapOf("distributed" to 12, "consensus" to 9, "common" to 15),
            1 to mapOf("parsing" to 10, "grammar" to 8, "common" to 14),
        )
        val labels = TopicLabels.label(wordCounts, topN = 2)
        assertEquals(listOf("distributed", "consensus"), labels.getValue(0))
        assertEquals(listOf("parsing", "grammar"), labels.getValue(1))
    }

    @Test
    fun `a term only one cluster has scores higher than an equally frequent shared one`() {
        val wordCounts = mapOf(
            0 to mapOf("unique" to 5, "shared" to 5),
            1 to mapOf("shared" to 5),
        )
        val labels = TopicLabels.label(wordCounts, topN = 1)
        assertEquals(listOf("unique"), labels.getValue(0))
    }

    @Test
    fun `topN limits the result and a cluster with no words gets an empty list`() {
        val wordCounts = mapOf(
            0 to mapOf("a" to 3, "b" to 2, "c" to 1),
            1 to emptyMap(),
        )
        val labels = TopicLabels.label(wordCounts, topN = 2)
        assertEquals(2, labels.getValue(0).size)
        assertTrue(labels.getValue(1).isEmpty())
    }

    @Test
    fun `no clusters yields no labels`() {
        assertTrue(TopicLabels.label(emptyMap()).isEmpty())
    }
}
