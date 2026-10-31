package dev.raggedlib.extract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfTextTest {
    @Test
    fun `splits pages on form feed and drops trailing empty page`() {
        val raw = "page one\n\u000Cpage two\n\u000C\u000Cpage four\n\u000C"
        val pages = PdfText.splitPages(raw)
        assertEquals(4, pages.size)
        assertEquals("page one\n", pages[0])
        assertEquals("", pages[2])
        assertEquals("page four\n", pages[3])
    }

    @Test
    fun `paragraphs carry 1-based page numbers and are de-hyphenated`() {
        val p1 = "The quick brown fox jumps over the lazy dog and keeps on running far away from here.\n" +
            "A stack buffer over-\nflow occurs when a program writes more data than the buffer holds.\n"
        val p2 = "\nSecond page paragraph with a well-known compound and plenty of text in it.\n\n42\n"
        val paras = PdfText.paragraphs(p1 + "\u000C" + p2 + "\u000C")
        assertEquals(listOf(1, 2), paras.map { it.page })
        assertTrue(paras[0].text.contains("buffer overflow occurs"), paras[0].text)
        assertTrue(!paras[0].text.contains("\n"))
        assertTrue(paras[1].text.contains("well-known"))
        assertTrue(paras.none { it.text == "42" }, "bare page numbers are dropped")
    }

    @Test
    fun `ligatures and soft hyphens are normalised`() {
        val paras = PdfText.paragraphs("The efﬁcient con­figuration of ﬂow control matters a lot here.\u000C")
        assertEquals("The efficient configuration of flow control matters a lot here.", paras.single().text)
    }
}
