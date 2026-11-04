package dev.raggedlib.vectors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectionCacheTest {
    private val P = VectorLevel.PASSAGES
    private val computed = mutableListOf<String>()
    private var fingerprints = mutableMapOf(1L to "a", 2L to "b")
    private var failNext = false

    private fun cache(maxEntries: Int = 16) = ProjectionCache(
        compute = { lib, level, method, size, seed ->
            if (failNext) {
                failNext = false
                error("projection failed")
            }
            computed += (if (level == VectorLevel.BOOKS) "books:" else "") + "$lib/$method/$size/$seed"
            VectorSpace(lib, level, method, seed, size, 0, 0, 0, null, 0, emptyList())
        },
        fingerprint = { fingerprints.getValue(it) },
        maxEntries = maxEntries,
    )

    @Test
    fun `the same library, method and size is computed once`() {
        val c = cache()
        assertFalse(c.get(1, P, VectorMethod.UMAP, 1000).cached)
        assertTrue(c.get(1, P, VectorMethod.UMAP, 1000).cached)
        c.get(1, P, VectorMethod.PCA, 1000)
        c.get(1, P, VectorMethod.UMAP, 500)
        c.get(2, P, VectorMethod.UMAP, 1000)
        assertEquals(listOf("1/UMAP/1000/1", "1/PCA/1000/1", "1/UMAP/500/1", "2/UMAP/1000/1"), computed)
    }

    @Test
    fun `a change to the library's passages invalidates its layouts only`() {
        val c = cache()
        c.get(1, P, VectorMethod.UMAP, 1000)
        c.get(2, P, VectorMethod.UMAP, 1000)
        fingerprints[1] = "a2"
        assertFalse(c.get(1, P, VectorMethod.UMAP, 1000).cached)
        assertTrue(c.get(2, P, VectorMethod.UMAP, 1000).cached)
        assertEquals(3, computed.size)
    }

    @Test
    fun `a reshuffle makes the new sample the library's current one, for every method`() {
        val c = cache()
        c.get(1, P, VectorMethod.UMAP, 1000)
        c.get(1, P, VectorMethod.PCA, 1000)
        assertEquals(42, c.get(1, P, VectorMethod.UMAP, 1000, seed = 42).seed)
        // without a seed (a page load, another method): the reshuffled sample, not the old one
        assertTrue(c.get(1, P, VectorMethod.UMAP, 1000).cached)
        assertEquals(42, c.get(1, P, VectorMethod.PCA, 1000).seed)
        assertEquals(1, c.get(2, P, VectorMethod.PCA, 1000).seed, "other libraries keep their sample")
        assertEquals(listOf("1/UMAP/1000/1", "1/PCA/1000/1", "1/UMAP/1000/42", "1/PCA/1000/42", "2/PCA/1000/1"), computed)
    }

    @Test
    fun `passages and books are cached apart, and share the library's sample`() {
        val c = cache()
        c.get(1, P, VectorMethod.UMAP, 1000)
        assertFalse(c.get(1, VectorLevel.BOOKS, VectorMethod.UMAP, 1000).cached)
        assertTrue(c.get(1, VectorLevel.BOOKS, VectorMethod.UMAP, 1000).cached)
        c.get(1, VectorLevel.BOOKS, VectorMethod.UMAP, 1000, seed = 9)
        assertEquals(9, c.get(1, P, VectorMethod.UMAP, 1000).seed)
        assertEquals(listOf("1/UMAP/1000/1", "books:1/UMAP/1000/1", "books:1/UMAP/1000/9", "1/UMAP/1000/9"), computed)
    }

    @Test
    fun `a failure isn't cached`() {
        val c = cache()
        failNext = true
        assertFailsWith<IllegalStateException> { c.get(1, P, VectorMethod.TSNE, 250) }
        assertFalse(c.get(1, P, VectorMethod.TSNE, 250).cached)
    }

    @Test
    fun `beyond the limit, the least recently used layout goes first`() {
        val c = cache(maxEntries = 2)
        c.get(1, P, VectorMethod.PCA, 250)
        c.get(1, P, VectorMethod.UMAP, 250)
        c.get(1, P, VectorMethod.PCA, 250) // PCA used last: UMAP is the one to go
        c.get(1, P, VectorMethod.TSNE, 250)
        assertTrue(c.get(1, P, VectorMethod.PCA, 250).cached)
        assertFalse(c.get(1, P, VectorMethod.UMAP, 250).cached)
    }
}
