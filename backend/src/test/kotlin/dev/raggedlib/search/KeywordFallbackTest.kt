package dev.raggedlib.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeywordFallbackTest {
    private val n = 300_000.0

    // document frequencies measured on the real library for "return oriented programming gadgets"
    private val rop = mapOf("return" to 0.1544, "program" to 0.1496, "orient" to 0.0217, "gadget" to 0.0006)

    @Test
    fun `rare terms nominate candidates, common ones only score`() {
        val plan = assertNotNull(KeywordFallback.plan(rop, n, commonMaxFrequency = 0.05, maxTerms = 12))
        assertEquals("'gadget' | 'orient'", plan.candidateQuery)
        assertEquals(listOf("gadget", "orient", "program", "return"), plan.terms.map { it.lexeme })
        assertEquals("'gadget' | 'orient' | 'program' | 'return'", plan.rankQuery)
    }

    @Test
    fun `coverage of the OOP false positive stays below the threshold`() {
        // the bug: a TOC page about object-oriented programming matched "orient" + "program" only
        val plan = assertNotNull(KeywordFallback.plan(rop, n, 0.05, 12))
        val w = plan.terms.associate { it.lexeme to it.weight }
        val oop = (w.getValue("orient") + w.getValue("program")) / plan.totalWeight
        val rop = (w.getValue("gadget") + w.getValue("orient") + w.getValue("return")) / plan.totalWeight
        assertTrue(oop < 0.5, "orient+program coverage was $oop")
        assertTrue(rop > 0.5, "gadget+orient+return coverage was $rop")
    }

    @Test
    fun `terms that occur nowhere are ignored`() {
        // "gadgest" (typo) would get the highest idf of all and sink every passage below the threshold
        val typo = rop - "gadget" + ("gadgest" to 0.0)
        val plan = assertNotNull(KeywordFallback.plan(typo, n, 0.05, 12))
        assertEquals(listOf("orient", "program", "return"), plan.terms.map { it.lexeme })
        assertNull(KeywordFallback.plan(mapOf("xyzzyq" to 0.0), n, 0.05, 12))
    }

    @Test
    fun `idf is positive and decreasing in frequency`() {
        val rare = KeywordFallback.idf(0.0006, n)
        val common = KeywordFallback.idf(0.15, n)
        val everywhere = KeywordFallback.idf(1.0, n)
        assertTrue(rare > common && common > everywhere && everywhere > 0.0)
        assertEquals(KeywordFallback.idf(0.0, n), KeywordFallback.idf(-1.0, n)) // clamped
    }

    @Test
    fun `no plan without terms or without any rare term`() {
        assertNull(KeywordFallback.plan(emptyMap(), n, 0.05, 12))
        assertNull(KeywordFallback.plan(mapOf("time" to 0.26, "test" to 0.11), n, 0.05, 12))
    }

    @Test
    fun `keeps only the rarest terms of long questions`() {
        val freqs = (1..20).associate { "t$it" to it / 1000.0 }
        val plan = assertNotNull(KeywordFallback.plan(freqs, n, 0.05, maxTerms = 5))
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), plan.terms.map { it.lexeme })
    }

    @Test
    fun `lexemes are quoted as literal tsquery operands`() {
        assertEquals("'gadget'", KeywordFallback.tsQueryLiteral("gadget"))
        assertEquals("'o''reilli'", KeywordFallback.tsQueryLiteral("o'reilli"))
        assertEquals("'a\\\\b'", KeywordFallback.tsQueryLiteral("a\\b"))
        assertEquals("'c++ & !x:*'", KeywordFallback.tsQueryLiteral("c++ & !x:*"))
    }

    @Test
    fun `detects websearch operators`() {
        assertTrue(KeywordFallback.usesWebsearchSyntax("\"return oriented\" gadgets"))
        assertTrue(KeywordFallback.usesWebsearchSyntax("rust -unsafe"))
        assertTrue(KeywordFallback.usesWebsearchSyntax("kafka or pulsar"))
        assertFalse(KeywordFallback.usesWebsearchSyntax("spring boot auto-configuration"))
        assertFalse(KeywordFallback.usesWebsearchSyntax("what is a monad"))
        assertFalse(KeywordFallback.usesWebsearchSyntax("ORM performance")) // "or" only as a word
        assertFalse(KeywordFallback.usesWebsearchSyntax("5\" floppy disk format")) // a lone quote isn't a phrase
    }
}
