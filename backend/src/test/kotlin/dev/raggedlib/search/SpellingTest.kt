package dev.raggedlib.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpellingTest {
    private fun words(q: String) = Spelling.tokens(q, 4).map { it.text }

    @Test
    fun `plain words are tokens, identifiers and short words are not`() {
        // stop words ("does") are tokens too; they are dropped later, having no lexeme
        assertEquals(listOf("does", "kubernets", "schedule", "pods"), words("how does kubernets schedule pods"))
        assertEquals(listOf("Retrun", "oriented"), words("Retrun oriented ROP"))
        // identifiers, flags, file names, exclusions, camelCase, acronyms
        assertEquals(emptyList(), words("MAP_FIXED tf.GradientTape std::move useEffect -kotlin x86asm SUID LD_PRELOAD git --onto"))
        assertEquals(listOf("flag"), words("setuid(0) shellcode_x flag"))
        // sentence punctuation is not part of a token, quotes are fine
        assertEquals(listOf("monad", "really"), words("a monad. \"really\"?"))
        assertEquals(listOf("pointer"), words("pointer: segfault.c"))
        // possessives and single quotes delimit; contractions are skipped
        assertEquals(listOf("Pyhton", "kubernets"), words("Pyhton's GIL 'kubernets' don't"))
        assertEquals(listOf("Kubernetes", "pods"), words("Kubernetes’ pods"))
    }

    @Test
    fun `token offsets cover the word as typed`() {
        val t = Spelling.tokens("why  Kubernets?", 4).single()
        assertEquals("Kubernets", "why  Kubernets?".substring(t.start, t.end))
    }

    @Test
    fun `edits1 has deletions, transpositions, replacements and insertions`() {
        val e = Spelling.edits1("gadgest")
        assertTrue("gadgets" in e) // transposition
        assertTrue("gadget" in e) // deletion
        assertTrue("gadgast" in e) // replacement
        assertTrue("gadgests" in e) // insertion
        assertFalse("gadgest" in e)
        assertTrue("bøkene" in Spelling.edits1("bokene") == false && "søøt" in Spelling.edits1("søt")) // own letters join the alphabet
    }

    @Test
    fun `distance counts a transposition as one edit`() {
        assertEquals(1, Spelling.distance("gadgest", "gadgets"))
        assertEquals(1, Spelling.distance("kubernets", "kubernetes"))
        assertEquals(2, Spelling.distance("asyncronus", "asynchronous"))
        assertEquals(0, Spelling.distance("same", "same"))
        assertEquals(4, Spelling.distance("", "abcd"))
    }

    private val rules = Spelling.Rules(longWordLength = 9, minCandidateDocs = 20, minFrequencyRatio = 200.0)
    private val unknown = Spelling.Occurrence(0, 0)
    private fun c(word: String, docs: Int, sameStem: Boolean = false) = Spelling.Candidate(word, docs, sameStem)

    @Test
    fun `an unknown word gets the closest, then the most frequent candidate`() {
        val gadget = listOf(c("gadgets", 900), c("gadget", 2000), c("badges", 5000))
        assertEquals("gadget", Spelling.choose("gadgest", unknown, gadget, rules)?.word)
        // a closer candidate beats a more frequent one further away
        val conc = listOf(c("concurrency", 300), c("concurrent", 9000))
        assertEquals("concurrency", Spelling.choose("concurrencyy", unknown, conc, rules)?.word)
        assertNull(Spelling.choose("schedul", unknown, listOf(c("schedule", 19)), rules)) // too rare a candidate
        assertNull(Spelling.choose("schedule", unknown, listOf(c("schedule", 4000)), rules)) // never itself
    }

    @Test
    fun `two edits only for long unknown words`() {
        val transaction = listOf(c("transaction", 10000))
        assertEquals("transaction", Spelling.choose("tarnsaciton", unknown, transaction, rules)?.word)
        assertNull(Spelling.choose("comptime", unknown, listOf(c("comptia", 300)), rules)) // 8 letters: one edit
        assertNull(Spelling.choose("attestable", Spelling.Occurrence(3, 3), listOf(c("testable", 1000)), rules))
    }

    @Test
    fun `the first letter stays, but the first two may be swapped`() {
        assertNull(Spelling.choose("nirie", unknown, listOf(c("sirie", 500)), rules))
        assertNull(Spelling.choose("maimed", unknown, listOf(c("aimed", 2000)), rules))
        assertEquals("program", Spelling.choose("rpogram", unknown, listOf(c("program", 50000)), rules)?.word)
        assertTrue(Spelling.sameStart("kubernets", "kubernetes"))
        assertFalse(Spelling.sameStart("hono", "mono"))
    }

    @Test
    fun `a word the library contains needs an overwhelmingly more frequent neighbour`() {
        // classic typos books contain too
        assertEquals("receive", Spelling.choose("recieve", Spelling.Occurrence(24, 24), listOf(c("receive", 11241)), rules)?.word)
        // real words with a common neighbour stay: the ratio is to the lexeme's count
        assertNull(Spelling.choose("minced", Spelling.Occurrence(0, 47), listOf(c("minded", 2000)), rules))
        assertNull(Spelling.choose("volter", Spelling.Occurrence(3, 3), listOf(c("voter", 227)), rules))
        // same lexeme: compared with the typed spelling only ("debuging" stems to debug, as debugging does)
        assertEquals("debugging", Spelling.choose("debuging", Spelling.Occurrence(0, 9000), listOf(c("debugging", 4000, sameStem = true)), rules)?.word)
        assertNull(Spelling.choose("debuging", Spelling.Occurrence(0, 9000), listOf(c("debugging", 4000)), rules))
    }

    @Test
    fun `apply keeps everything but the corrected words`() {
        val q = "How does Kubernets shedule \"pods\"?"
        val tokens = Spelling.tokens(q, 4)
        val fixes = mapOf("kubernets" to "kubernetes", "shedule" to "schedule")
        val replacements = tokens.mapNotNull { t -> fixes[t.lower]?.let { t to Spelling.matchCase(t.text, it) } }.toMap()
        assertEquals("How does Kubernetes schedule \"pods\"?", Spelling.apply(q, replacements))
    }
}
