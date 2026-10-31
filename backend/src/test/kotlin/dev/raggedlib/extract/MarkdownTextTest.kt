package dev.raggedlib.extract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTextTest {
    @Test
    fun `tracks headings as sections and cleans inline markdown`() {
        val md = """
            ## Table of Contents

            1.  Preface
            2.  Layers

            ## 1. What’s Wrong With Layers?

            Chances are that you have developed a layered \(web\) application in the past\! See [the docs](http://x.y/z) and **this**.

            ![figure](images/fig1.png)

            ### It Promotes Database-Driven Design

            By its very definition, the foundation of a conventional layered architecture is the database.
        """.trimIndent()
        val paras = MarkdownText.paragraphs(md)
        assertTrue(paras.none { it.text.contains("Preface") }, "TOC section skipped")
        val body = paras.first { it.text.startsWith("Chances") }
        assertEquals("1. What’s Wrong With Layers?", body.section)
        assertEquals("Chances are that you have developed a layered (web) application in the past! See the docs and this.", body.text)
        assertEquals("It Promotes Database-Driven Design", paras.last().section)
        assertTrue(paras.none { it.text.contains("fig1") })
    }

    @Test
    fun `strips emphasis markers but keeps arithmetic and identifiers`() {
        assertEquals("Same-Origin Policy", MarkdownText.cleanInline("****Same-Origin Policy****"))
        assertEquals("The same-origin policy (SOP) is", MarkdownText.cleanInline("The *same-origin policy* (*SOP*) is"))
        assertEquals("a * b * c and my_var_name", MarkdownText.cleanInline("a * b * c and my_var_name"))
    }
}
