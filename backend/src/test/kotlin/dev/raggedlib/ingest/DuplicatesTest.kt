package dev.raggedlib.ingest

import dev.raggedlib.ingest.Duplicates.Match
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DuplicatesTest {
    private fun keeper(id: Long, chunks: Int, vararg m: Match) =
        Duplicates.keeper(id, LIB, chunks, chunks, m.toList(), 0.9, 5) { error("same library: not asked") }

    private fun Match(bookId: Long, shared: Int, chunks: Int) = Duplicates.Match(bookId, shared, chunks, LIB)

    @Test
    fun `of two identical copies the lower id is kept`() {
        assertEquals(746L, keeper(1589, 537, Match(746, 537, 537))?.bookId)
        assertNull(keeper(746, 537, Match(1589, 537, 537)))
    }

    @Test
    fun `a book contained in a longer copy is the duplicate, not the longer one`() {
        // Reversing: 760 of 783 passages in the 784-passage copy
        assertEquals(1591L, keeper(260, 783, Match(1591, 760, 784))?.bookId)
        assertNull(keeper(1591, 784, Match(260, 760, 783)))
    }

    @Test
    fun `shared front matter or part of an edition is not a duplicate`() {
        assertNull(keeper(409, 264, Match(408, 85, 477))) // volumes sharing introductions
        assertNull(keeper(898, 386, Match(906, 102, 416))) // another edition
        assertNull(keeper(10, 3, Match(11, 2, 900))) // 2 of 3: under 90%
        assertEquals(11L, keeper(10, 3, Match(11, 3, 900))?.bookId) // all of a tiny book
    }

    @Test
    fun `with several candidates the one sharing most wins`() {
        assertEquals(2L, keeper(9, 100, Match(1, 91, 100), Match(2, 99, 120), Match(3, 95, 500))?.bookId)
    }

    @Test
    fun `across libraries only an exact copy is kept, so its passages are all the duplicate's own`() {
        val distinct = mapOf(1L to 100, 2L to 5000, 3L to 101)
        fun keeper(vararg m: Duplicates.Match) = Duplicates.keeper(9, LIB, 100, 100, m.toList(), 0.9, 5) { distinct.getValue(it) }

        assertNull(keeper(Duplicates.Match(2, 100, 5000, OTHER)), "an excerpt of a longer book elsewhere")
        assertNull(keeper(Duplicates.Match(1, 95, 100, OTHER)), "nearly the same book elsewhere")
        assertNull(keeper(Duplicates.Match(3, 100, 101, OTHER)), "the same passages and one more")
        assertEquals(1L, keeper(Duplicates.Match(1, 100, 100, OTHER))?.bookId, "the same book elsewhere")
        assertEquals(2L, keeper(Duplicates.Match(2, 100, 5000, LIB))?.bookId, "an excerpt in the same library")
    }

    @Test
    fun `md5 matches Postgres md5(text)`() {
        assertEquals("7e1e84379e8e3c749a3abaa39abff5dd", Duplicates.md5("Grüße – λ")) // select md5('Grüße – λ')
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Duplicates.md5("abc"))
    }

    @Test
    fun `parses pgvector text`() {
        assertContentEquals(floatArrayOf(0.1f, -2f, 3e-5f), ChunkStore.parseVector("[0.1,-2,3e-05]"))
        assertContentEquals(floatArrayOf(), ChunkStore.parseVector("[]"))
    }

    private companion object {
        const val LIB = 1L
        const val OTHER = 2L
    }
}
