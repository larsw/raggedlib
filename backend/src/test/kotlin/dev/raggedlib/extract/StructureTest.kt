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
        // a chapter number isn't a page number
        assertEquals("Chapter 3", Structure.headingOf("Chapter 3"))
        assertEquals("Part II", Structure.headingOf("Part II"))
        // letter-spaced display type
        assertEquals("Chapter 8", Structure.headingOf("C h apter 8"))
        assertEquals("Chapter 10", Structure.headingOf("C h apter 1 0"))
        assertNull(Structure.headingOf("a b c 8"))
    }

    @Test
    fun `a bare chapter line takes its title from the next line on the same page`() {
        val paras = page(309, "C h apter 8", "Common Debugging Scenarios, Part 1", "Memory comes from virtual allocations.") +
            page(400, "Chapter 9", "Unlike the previous chapter, this one is about races.")
        val out = Structure.inferSections(paras)
        assertEquals("Chapter 8 Common Debugging Scenarios, Part 1", out[2].section)
        assertEquals("Chapter 9", out[4].section) // a sentence isn't a title
    }

    private fun page(n: Int, vararg texts: String) = texts.map { Paragraph(it, n, null) }

    @Test
    fun `table of contents entries don't label the front matter`() {
        val paras = page(1, "Inside Windows Debugging", "Tarik Soulami") +
            page(5, "Contents", "Chapter 1 Software Development Tools 3", "Chapter 2 Getting Started 35",
                "Chapter 3 How Windows Debuggers Work 79") +
            page(6, "Chapter 14 WinDbg User-Mode Debugging Quick Start", "Chapter 15 WinDbg Kernel-Mode Debugging Quick Start") +
            page(7, "Foreword", "Like many others, I am a firm believer in using tools to expand our understanding.") +
            page(50, "Chapter 1 Software Development Tools", "In this chapter, you will learn about the tools.")
        val out = Structure.inferSections(paras)
        assertEquals(setOf(5, 6), Structure.tocPages(paras))
        assertNull(out.single { it.text.startsWith("Like many") }.section)
        assertEquals("Chapter 1 Software Development Tools", out.single { it.text.startsWith("In this chapter") }.section)
    }

    @Test
    fun `dotted contents lines mark a contents page, even though cleanup drops them`() {
        val paras = page(15, "Analyzing Blocked Time. . . . . . . . . . . . . . . 458",
            "Wait Analysis Using Xperf. . . . . . . . . . . . . . . 467", "Tracing as a Debugging Aid. . . . . . . . . . . 490") +
            page(16, "Ending a User-Mode Debugging Session. . . . . . . . . . . 518",
                "Chapter 15 WinDbg Kernel-Mode Debugging Quick Start", "519")
        assertEquals(setOf(15, 16), Structure.tocPages(paras)) // 16 continues the contents
    }

    @Test
    fun `a chapter page with one heading is not a contents page`() {
        val paras = page(3, "Chapter 2 Getting Started", "2.1 Installing the tools",
            "As discussed in Chapter 1, the debugger needs symbols.", "2.2 First steps")
        assertEquals(emptySet(), Structure.tocPages(paras))
        assertEquals("2.2 First steps", Structure.inferSections(paras).last().section)
    }

    @Test
    fun `body pages with chapter references or number tables are not contents`() {
        val ref = page(95, "Table 4-1 lists the members; Chapter 5 covers primitives, Chapter 6 members, Chapter 8 methods " +
            "and Chapter 10 properties. The rest of this chapter explains how they fit together in the runtime.",
            "Chapter 4 Type Fundamentals")
        val table = page(30, "Application - 2", "Application - 4", "Application - 6", "Application - 8",
            "The diagram shows how the applications talk to each other through the gateway. Each one owns its data, " +
                "and none of them reaches into another's database. That is the point of the pattern.")
        assertEquals(emptySet(), Structure.tocPages(ref + table))
    }

    @Test
    fun `untitled contents pages with a page-number column are contents`() {
        val paras = page(9, "Protocol Evolution Final Remarks References", "68 73 73",
            "Chapter 4 SSL Protocol 4.1 Introduction 4.2 Protocols 4.2.1 SSL Record Protocol 4.2.2 SSL Handshake Protocol",
            "75 75 87 87 94 117", "Chapter 5 TLS Protocol 5.1 Introduction 5.1.1 TLS PRF 5.2 TLS 1.0 5.2.1 Cipher Suites",
            "133 133 136 139 141")
        assertEquals(setOf(9), Structure.tocPages(paras))
    }

    @Test
    fun `a part opener right after the contents is not more contents`() {
        val paras = page(10, "Contents", "Part I Preface 1", "Chapter 1 Structured Query Language 9", "Chapter 2 Software 19") +
            page(11, "Part I Preface")
        assertEquals(setOf(10), Structure.tocPages(paras))
    }

    @Test
    fun `a mini-contents inside a chapter keeps the chapter's label`() {
        val paras = page(5, "Chapter 1 Basics", "Text of chapter one. It goes on for a while.") +
            page(6, "Topics . . . . . . . 7", "More . . . . . . . 9", "Other . . . . . . . 11") +
            page(7, "Still chapter one. Really.")
        val out = Structure.inferSections(paras)
        assertEquals("Chapter 1 Basics", out.last().section)
    }

    @Test
    fun `contents detection stays near the front`() {
        // a table-heavy page in the middle of a long book must not reset its chapter
        val body = (1..400).map { Paragraph("Some text on page $it of the book.", it, null) }
        val table = page(300, "1.1 Alpha 10", "1.2 Beta 20", "1.3 Gamma 30", "1.4 Delta 40", "1.5 Epsilon 50")
        assertEquals(emptySet(), Structure.tocPages(body + table))
    }
}
