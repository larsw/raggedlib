package dev.raggedlib.extract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OcrTextTest {
    @Test
    fun `parses tesseract's language list`() {
        val out = "List of available languages in \"/usr/share/tesseract-ocr/5/tessdata/\" (3):\neng\nnor\nosd\n"
        assertEquals(setOf("eng", "nor", "osd"), OcrText.installedLanguages(out))
    }

    @Test
    fun `book languages come first, then the configured ones, as far as installed`() {
        val installed = setOf("eng", "nor", "deu", "osd")
        assertEquals("nor+eng", OcrText.languages(listOf("nob"), "eng+nor", installed))
        assertEquals("deu+eng", OcrText.languages(listOf("deu", "fra"), "eng", installed))
        assertEquals("eng", OcrText.languages(emptyList(), "eng+fra", installed))
        assertNull(OcrText.languages(listOf("fra"), "spa", installed))
        assertNull(OcrText.languages(emptyList(), "osd", installed))
    }

    @Test
    fun `confidence is weighted by word length and ignores non-word rows`() {
        val tsv = """
            level	page_num	block_num	par_num	line_num	word_num	left	top	width	height	conf	text
            1	1	0	0	0	0	0	0	2480	3508	-1
            4	1	1	1	1	0	100	100	800	40	-1
            5	1	1	1	1	1	100	100	300	40	96.0	Intelligence
            5	1	1	1	1	2	420	100	60	40	20.0	a
            5	1	1	1	1	3	500	100	60	40	-1
        """.trimIndent()
        // (96 * 12 + 20 * 1) / 13
        assertEquals(90.15, OcrText.confidence(tsv), 0.01)
        assertEquals(0.0, OcrText.confidence(tsv.lines().take(3).joinToString("\n")))
    }

    @Test
    fun `image coverage of scanned and born-digital pages`() {
        val info = """
            Pages:           3
            Page    1 size: 595.32 x 841.92 pts (A4)
            Page    2 size: 595.32 x 841.92 pts (A4)
            Page    3 size: 842.4 x 595.68 pts (A4)
        """.trimIndent()
        val images = """
            page   num  type   width height color comp bpc  enc interp  object ID x-ppi y-ppi size ratio
            --------------------------------------------------------------------------------------------
               1     0 image    4957  5288  rgb     3   8  jpeg   no         4  0   600   600  787K 1.0%
               1     1 image    4957  1727  rgb     3   8  jpeg   no         5  0   600   600  158K 0.6%
               1     2 smask    4957  1727  gray    1   8  image  no         6  0   600   600  158K 0.6%
               2     3 image     600   400  rgb     3   8  jpeg   no         9  0   300   300  20K  1.0%
               3     4 image    3510  2482  gray    1   1  ccitt  no        20  0   300   300 79.1K 7.4%
        """.trimIndent()
        val coverage = OcrText.imageCoverage(images, OcrText.pageSizes(info))
        assertEquals(3, OcrText.pageSizes(info).size)
        // two strips of one scanned A4 page (the soft mask doesn't count twice)
        assertEquals(1.0, coverage.getValue(1), 0.05)
        assertTrue(coverage.getValue(2) < 0.1) // a 2 x 1.3 inch figure
        assertEquals(1.0, coverage.getValue(3), 0.01)
    }

    @Test
    fun `candidate pages are listed in few ranges`() {
        assertEquals(listOf(2..2, 34..34, 69..69), OcrText.ranges(listOf(69, 2, 34), 20))
        assertEquals(listOf(461..467, 469..469), OcrText.ranges(listOf(461, 462, 463, 464, 465, 466, 467, 469), 20))
        // too many: the closest runs are merged first
        assertEquals(listOf(1..5, 100..100), OcrText.ranges(listOf(1, 3, 5, 100), 2))
        assertEquals(listOf(1..100), OcrText.ranges(listOf(1, 3, 5, 100), 1))
        assertEquals(emptyList(), OcrText.ranges(emptyList(), 20))
    }

    @Test
    fun `text layer needs enough letters in all and per page`() {
        assertTrue(OcrText.hasTextLayer(listOf(2000, 0, 1500), 80))
        assertFalse(OcrText.hasTextLayer(listOf(0, 0, 0), 80))
        assertFalse(OcrText.hasTextLayer(listOf(400), 80)) // too little in all
        assertFalse(OcrText.hasTextLayer(List(100) { if (it < 5) 1000 else 20 }, 80)) // a cover page, the rest scanned
    }

    @Test
    fun `pages to OCR`() {
        val letters = listOf(0, 1500, 30, 0, 2000)
        // a scan: every page short of text, whatever it holds
        assertEquals(listOf(1, 3, 4), OcrText.pagesToOcr(letters, 80, hasTextLayer = false, coverage = emptyMap(), minCoverage = 0.5))
        // a born-digital PDF: only textless pages that are mostly image
        val coverage = mapOf(1 to 0.02, 3 to 0.95, 4 to 0.6)
        assertEquals(listOf(3, 4), OcrText.pagesToOcr(letters, 80, hasTextLayer = true, coverage = coverage, minCoverage = 0.5))
        assertEquals(emptyList(), OcrText.pagesToOcr(letters, 80, hasTextLayer = true, coverage = emptyMap(), minCoverage = 0.5))
    }
}
