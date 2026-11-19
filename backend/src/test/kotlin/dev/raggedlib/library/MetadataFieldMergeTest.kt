package dev.raggedlib.library

import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataFieldMergeTest {
    @Test
    fun `changed fields save when their opening values are still current`() {
        val result = mergeMetadataFields(
            original = mapOf("tags" to listOf("old"), "publisher" to "Before"),
            current = mapOf("tags" to listOf("old"), "publisher" to "Before"),
            requested = mapOf("tags" to listOf("mine")),
        )

        assertEquals(emptyMap(), result.conflicts)
        assertEquals(mapOf("tags" to listOf("mine")), result.changes)
    }

    @Test
    fun `a changed field conflict refuses every requested write`() {
        val result = mergeMetadataFields(
            original = mapOf("tags" to listOf("old"), "publisher" to "Before"),
            current = mapOf("tags" to listOf("theirs"), "publisher" to "Before"),
            requested = mapOf("tags" to listOf("mine"), "publisher" to "Mine Press"),
        )

        assertEquals(mapOf("tags" to listOf("theirs")), result.conflicts)
        assertEquals(emptyMap(), result.changes)
    }

    @Test
    fun `a change to an untouched field is preserved without a conflict`() {
        val result = mergeMetadataFields(
            original = mapOf("tags" to listOf("old"), "publisher" to "Before"),
            current = mapOf("tags" to listOf("old"), "publisher" to "Calibre update"),
            requested = mapOf("tags" to listOf("mine")),
        )

        assertEquals(emptyMap(), result.conflicts)
        assertEquals(mapOf("tags" to listOf("mine")), result.changes)
    }
}
