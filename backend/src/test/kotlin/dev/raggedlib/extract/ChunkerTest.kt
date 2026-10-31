package dev.raggedlib.extract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkerTest {
    private val chunker = Chunker(target = 1500, max = 2000, overlap = 200, minChars = 80)

    private fun sentence(i: Int) = "This is sentence number $i about buffer overflows and stack canaries in C programs."

    private fun paragraph(start: Int, n: Int) = (start until start + n).joinToString(" ") { sentence(it) }

    @Test
    fun `packs paragraphs up to target and never exceeds max`() {
        val paras = (0 until 40).map { Paragraph(paragraph(it * 5, 5), page = it / 4 + 1, section = null) }
        val chunks = chunker.chunk(paras)
        assertTrue(chunks.size > 5)
        chunks.forEach { assertTrue(it.text.length <= 2000 + 200, "chunk too long: ${it.text.length}") }
        chunks.dropLast(1).forEach { assertTrue(it.text.length >= 1000, "chunk too short: ${it.text.length}") }
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
    }

    @Test
    fun `consecutive chunks overlap by trailing sentences`() {
        val paras = (0 until 20).map { Paragraph(paragraph(it * 5, 5), page = 1, section = null) }
        val chunks = chunker.chunk(paras)
        for (i in 1 until chunks.size) {
            val prevLastSentence = chunks[i - 1].text.trim().substringAfterLast(". ").trim()
            assertTrue(chunks[i].text.take(250).contains(prevLastSentence), "chunk $i does not start with overlap")
            assertTrue(!chunks[i].text.startsWith(chunks[i - 1].text.take(100)))
        }
    }

    @Test
    fun `oversized paragraph is split at sentence boundaries`() {
        val huge = paragraph(0, 100) // ~8500 chars, one paragraph
        val chunks = chunker.chunk(listOf(Paragraph(huge, 7, "Intro")))
        assertTrue(chunks.size >= 4)
        chunks.forEach {
            assertTrue(it.text.length <= 2000)
            assertTrue(it.text.trimEnd().endsWith("."), "should end on a sentence boundary")
            assertEquals(7, it.page)
            assertEquals("Intro", it.section)
        }
    }

    @Test
    fun `page of chunk is page of first new paragraph`() {
        val paras = (1..10).map { Paragraph(paragraph(it * 10, 6), page = it, section = null) }
        val chunks = chunker.chunk(paras)
        assertEquals(1, chunks.first().page)
        val pages = chunks.map { it.page!! }
        assertEquals(pages.sorted(), pages)
        assertTrue(pages.last() > 5)
    }

    @Test
    fun `new section starts a new chunk once the current one is reasonably full`() {
        val paras = listOf(
            Paragraph(paragraph(0, 8), null, "Chapter 1"),
            Paragraph("Chapter 2", null, "Chapter 2"),
            Paragraph(paragraph(100, 8), null, "Chapter 2"),
        )
        val chunks = chunker.chunk(paras)
        assertEquals("Chapter 1", chunks[0].section)
        assertTrue(chunks[0].text.contains("number 7 "))
        assertTrue(!chunks[0].text.contains("Chapter 2"))
        assertEquals("Chapter 2", chunks[1].section)
        assertTrue(chunks[1].text.startsWith("Chapter 2"))
    }

    @Test
    fun `junk chunks like dot-leader TOCs and number soup are dropped`() {
        val toc = (1..40).joinToString("\n") { "Chapter $it Something .......... ${it * 10}" }
        val numbers = (1..300).joinToString(" ") { "${it * 37 % 1000}" }
        val chunks = chunker.chunk(listOf(Paragraph(toc, 2, null), Paragraph(numbers, 3, null), Paragraph(paragraph(0, 3), 4, null)))
        assertEquals(1, chunks.size)
        assertEquals(4, chunks[0].page)
    }
}

class IndexJunkTest {
    @Test
    fun `back-of-book index without page numbers is junk, prose is not`() {
        val index = "Same-Origin Policy for XMLHttpRequest, Set-Cookie headers, HTTP Cookie Semantics, setters, in JavaScript, " +
            "Overriding, Logic, Interactions with Browser Credentials, Security Policy for Cookies, DNS pinning, " +
            "Access to Internal Networks, Document Object Model, frames, navigation, plug-ins, Flash, Java, Silverlight, " +
            "content sniffing, MIME types, character sets, UTF-7, CSS, expressions, selectors, fonts, history, cache"
        kotlin.test.assertTrue(TextCleaner.isJunkChunk(index, 80))
        val prose = "The same-origin policy, in short, restricts how a document or script loaded from one origin can interact " +
            "with a resource from another origin. Cookies, however, follow a different and older set of rules. " +
            "They are scoped by domain and path, not by origin, which leads to a number of subtle problems."
        kotlin.test.assertFalse(TextCleaner.isJunkChunk(prose, 80))
    }
}
