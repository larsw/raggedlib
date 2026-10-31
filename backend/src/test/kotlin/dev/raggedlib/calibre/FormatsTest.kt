package dev.raggedlib.calibre

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormatsTest {
    @Test
    fun `prefers EPUB over AZW3 over MOBI over FB2 over PDF over LIT over CHM over DJVU`() {
        assertEquals("EPUB", Formats.choose(listOf("PDF", "EPUB", "MOBI")))
        assertEquals("AZW3", Formats.choose(listOf("PDF", "MOBI", "AZW3")))
        assertEquals("MOBI", Formats.choose(listOf("pdf", "mobi")))
        assertEquals("FB2", Formats.choose(listOf("PDF", "FB2")))
        assertEquals("PDF", Formats.choose(listOf("DJVU", "PDF", "CHM")))
        assertEquals("LIT", Formats.choose(listOf("DJVU", "LIT", "CHM")))
        assertEquals("CHM", Formats.choose(listOf("DJVU", "CHM")))
        assertEquals("DJVU", Formats.choose(listOf("DJVU", "ZIP")))
    }

    @Test
    fun `unsupported formats yield null`() {
        assertNull(Formats.choose(listOf("ZIP", "ORIGINAL_EPUB")))
        assertNull(Formats.choose(emptyList()))
    }
}
