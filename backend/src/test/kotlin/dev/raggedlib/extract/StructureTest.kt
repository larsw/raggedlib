package dev.raggedlib.extract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructureTest {
    @Test
    fun `strips recurring running headers and page numbers from PDF pages`() {
        val words = listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta")
        val pages = (1..30).map { n ->
            "620Xch01final.qxd\n\n9/22/06\n\n12:${n % 60} AM\n\nPage $n\n\nBody text of page $n talks about ${words[n % 6]} ${words[n / 6 % 6]} inference.\n\n$n\n"
        }
        val cleaned = Structure.stripPageFurniture(pages)
        cleaned.forEachIndexed { i, p ->
            assertFalse(p.contains("qxd") || p.contains("AM") || p.contains("Page ${i + 1}"), p)
            assertTrue(p.contains("Body text of page ${i + 1}"))
        }
    }

    @Test
    fun `merges paragraphs broken mid-sentence`() {
        val merged = Structure.mergeBroken(
            listOf(Paragraph("The purpose of this book is to explain the use of the GNU C and C++", 1, null),
                Paragraph("compilers, gcc and g++.", 2, null), Paragraph("Next sentence.", 2, null)),
            8000,
        )
        assertEquals(2, merged.size)
        assertEquals("The purpose of this book is to explain the use of the GNU C and C++ compilers, gcc and g++.", merged[0].text)
        assertEquals(1, merged[0].page)
    }

    @Test
    fun `detects plain-text headings`() {
        assertEquals("Chapter 10: Compiler-related tools", Structure.headingOf("Chapter 10: Compiler-related tools 73"))
        assertEquals("1.3 Programming in C and C++", Structure.headingOf("1.3 Programming in C and C++"))
        assertNull(Structure.headingOf("1.3 Programming in C and C++ 12"))
        assertNull(Structure.headingOf("This is a normal sentence."))
        assertNull(Structure.headingOf("42"))
    }
}
