package dev.raggedlib.vectors

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectionsTest {
    /** Two clusters of 20 points in 10 dimensions, centred on two orthogonal axes (cosine-separated). */
    private fun twoClusters(seed: Int = 7): Array<DoubleArray> {
        val rnd = Random(seed)
        return Array(40) { i ->
            DoubleArray(10) { j -> (if (j == (if (i < 20) 0 else 1)) 10.0 else 0.0) + rnd.nextDouble(-1.0, 1.0) }
        }
    }

    /** Ratio of the distance between the two cluster centroids to the largest spread within a cluster. */
    private fun separation(p: Array<DoubleArray>): Double {
        val dims = p[0].size // 2-D or 3-D layouts
        fun dist(u: DoubleArray, v: DoubleArray) = sqrt((0 until dims).sumOf { (u[it] - v[it]) * (u[it] - v[it]) })
        fun centroid(range: IntRange) = DoubleArray(dims) { c -> range.map { p[it][c] }.average() }
        fun spread(range: IntRange, c: DoubleArray) = range.maxOf { i -> dist(p[i], c) }
        val a = centroid(0 until 20)
        val b = centroid(20 until 40)
        val between = dist(a, b)
        val within = maxOf(spread(0 until 20, a), spread(20 until 40, b))
        return between / within
    }

    @Test
    fun `pca finds the direction of greatest variance`() {
        val rnd = Random(3)
        val direction = doubleArrayOf(1.0, 2.0, 0.0, 0.0, 0.0).map { it / sqrt(5.0) }.toDoubleArray()
        val x = Array(200) { i ->
            val t = (i - 100) * 0.5
            DoubleArray(5) { j -> t * direction[j] + rnd.nextDouble(-0.05, 0.05) }
        }
        val pca = Projections.pca(x)
        assertTrue(pca.explainedVariance[0] > 0.98, "first axis explains ${pca.explainedVariance[0]}")
        assertTrue(pca.explainedVariance[0] >= pca.explainedVariance[1])
        // the first coordinate is linear in t, so it is monotone (up to sign) along the sample order
        val first = pca.coords.map { it[0] }
        val increasing = first.zipWithNext().count { (a, b) -> b > a }
        assertTrue(increasing == 0 || increasing == first.size - 1, "not monotone: $increasing increases")
    }

    @Test
    fun `squared cosine distances of unit-normalised rows`() {
        val d = Projections.squaredCosineDistances(
            arrayOf(floatArrayOf(3f, 0f), floatArrayOf(6f, 0f), floatArrayOf(0f, 2f)),
        )
        val n = 3
        assertEquals(0f, d[0 * n + 1], 1e-6f) // same direction, different length
        assertEquals(2f, d[0 * n + 2], 1e-6f) // orthogonal
        assertEquals(2f, d[2 * n + 0], 1e-6f)
        assertEquals(0f, d[1 * n + 1], 0f)
    }

    @Test
    fun `tsne keeps separated clusters apart`() {
        val x = twoClusters()
        val init = Projections.pca(x).coords
        val sq = Projections.squaredCosineDistances(Array(x.size) { i -> FloatArray(10) { x[i][it].toFloat() } })
        // the default iterations: at 400, whether the layout has settled yet comes down to rounding (summation order)
        val layout = Projections.tsne(sq, x.size, init, perplexity = 10.0)
        assertEquals(x.size, layout.size)
        assertTrue(layout.all { row -> row.all { it.isFinite() } })
        assertTrue(separation(layout) > 2.0, "separation ${separation(layout)}")
    }

    @Test
    fun `umap keeps separated clusters apart`() {
        val x = twoClusters()
        val init = Projections.pca(x).coords
        val sq = Projections.squaredCosineDistances(Array(x.size) { i -> FloatArray(10) { x[i][it].toFloat() } })
        val layout = Projections.umap(sq, x.size, init, neighbors = 10, epochs = 200, seed = 1)
        assertEquals(x.size, layout.size)
        assertTrue(layout.all { row -> row.all { it.isFinite() } })
        assertTrue(separation(layout) > 2.0, "separation ${separation(layout)}")
    }

    @Test
    fun `3-D layouts have three finite coordinates and keep clusters apart`() {
        val x = twoClusters()
        val init = Projections.pca(x, components = 3).coords
        val sq = Projections.squaredCosineDistances(Array(x.size) { i -> FloatArray(10) { x[i][it].toFloat() } })
        for (layout in listOf(
            Projections.umap(sq, x.size, init, neighbors = 10, epochs = 200, seed = 1),
            Projections.tsne(sq, x.size, init, perplexity = 10.0),
        )) {
            assertTrue(layout.all { row -> row.size == 3 && row.all { it.isFinite() } })
            // the third axis is used, not left at its starting value
            assertTrue(layout.map { it[2] }.distinct().size > 1)
            assertTrue(separation(layout) > 2.0, "separation ${separation(layout)}")
        }
    }

    @Test
    fun `the umap curve fit matches umap-learn's`() {
        // umap-learn's find_ab_params(spread=1, min_dist=0.1)
        val (a, b) = Projections.umapCurve(0.1)
        assertEquals(1.5769434, a, 1e-3)
        assertEquals(0.8950608, b, 1e-3)
    }

    @Test
    fun `a larger min_dist flattens the umap curve near zero`() {
        // the curve's value at a short distance: closer to 1 the more room min_dist leaves points
        fun at(minDist: Double, x: Double = 0.3): Double {
            val (a, b) = Projections.umapCurve(minDist)
            return 1.0 / (1.0 + a * Math.pow(x, 2 * b))
        }
        assertTrue(at(0.0) < at(0.1) && at(0.1) < at(0.5) && at(0.5) < at(0.9), "${listOf(0.0, 0.1, 0.5, 0.9).map { at(it) }}")
    }

    @Test
    fun `umap with another min_dist still keeps clusters apart`() {
        val x = twoClusters()
        val init = Projections.pca(x).coords
        val sq = Projections.squaredCosineDistances(Array(x.size) { i -> FloatArray(10) { x[i][it].toFloat() } })
        for (minDist in listOf(0.0, 0.5)) {
            val layout = Projections.umap(sq, x.size, init, neighbors = 10, epochs = 200, seed = 1, minDist = minDist)
            assertTrue(layout.all { row -> row.all { it.isFinite() } })
            assertTrue(separation(layout) > 2.0, "min_dist $minDist: separation ${separation(layout)}")
        }
    }

    @Test
    fun `projections are deterministic for a seed`() {
        val x = twoClusters(seed = 11)
        val init = Projections.pca(x, seed = 5).coords
        val sq = Projections.squaredCosineDistances(Array(x.size) { i -> FloatArray(10) { x[i][it].toFloat() } })
        val a = Projections.umap(sq, x.size, init, neighbors = 8, epochs = 50, seed = 9)
        val b = Projections.umap(sq, x.size, init, neighbors = 8, epochs = 50, seed = 9)
        assertTrue(a.indices.all { i -> abs(a[i][0] - b[i][0]) < 1e-12 && abs(a[i][1] - b[i][1]) < 1e-12 })
    }
}
