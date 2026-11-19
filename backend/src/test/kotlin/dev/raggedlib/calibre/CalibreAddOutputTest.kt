package dev.raggedlib.calibre

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CalibreAddOutputTest {
    @Test
    fun `reads one added id from calibredb output`() {
        assertEquals(42L, parseAddedCalibreBookId("Added book ids: 42\n", ""))
    }

    @Test
    fun `identifies a duplicate only from calibredb's duplicate notice`() {
        assertNull(
            parseAddedCalibreBookId(
                "",
                "The following books were not added as they already exist in the database:\n Book title\n",
            ),
        )
        assertFailsWith<IllegalStateException> { parseAddedCalibreBookId("", "unexpected output") }
    }
}
