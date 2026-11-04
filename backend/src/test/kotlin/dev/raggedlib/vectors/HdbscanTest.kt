package dev.raggedlib.vectors

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HdbscanTest {
    /**
     * [groupSizes] tight clusters, each centred on its own axis 20 from the origin (so any two centroids are
     * ~28 apart), plus [noise] points placed far outside that whole neighbourhood by construction — not drawn
     * from the same random cloud the clusters live in, so the test can't go flaky on an unlucky draw that happens
     * to land near a cluster's edge (clusters ~28 apart with a jitter radius of ~1 already leaves a thin margin).
     */
    private fun syntheticPoints(groupSizes: List<Int>, noise: Int, dims: Int = 5, seed: Int = 11): Array<DoubleArray> {
        val rnd = Random(seed)
        val points = ArrayList<DoubleArray>()
        groupSizes.forEachIndexed { g, size ->
            repeat(size) {
                points += DoubleArray(dims) { j -> (if (j == g) 20.0 else 0.0) + rnd.nextDouble(-0.5, 0.5) }
            }
        }
        // each noise point sits far out along its own direction, distinct from every cluster's and every other
        // noise point's, and far enough (≥ 200) that no amount of cluster jitter could ever close the gap
        repeat(noise) { i ->
            points += DoubleArray(dims) { j -> if (j == i % dims) (if (i < dims) 300.0 else -300.0) else 0.0 }
        }
        return points.toTypedArray()
    }

    private fun distances(points: Array<DoubleArray>): FloatArray {
        val n = points.size
        val out = FloatArray(n * n)
        for (i in 0 until n) for (j in i + 1 until n) {
            var d2 = 0.0
            for (c in points[i].indices) {
                val d = points[i][c] - points[j][c]
                d2 += d * d
            }
            val d = sqrt(d2).toFloat()
            out[i * n + j] = d
            out[j * n + i] = d
        }
        return out
    }

    @Test
    fun `recovers well-separated clusters and flags scattered points as noise`() {
        val groupSizes = listOf(15, 15, 15)
        val noise = 6
        val points = syntheticPoints(groupSizes, noise)
        val n = points.size
        val labels = Hdbscan.cluster(distances(points), n, minClusterSize = 5)

        // the three groups: every member shares one label, distinct from the other groups'
        var offset = 0
        val groupLabels = groupSizes.map { size ->
            val labelsInGroup = (offset until offset + size).map { labels[it] }
            offset += size
            assertTrue(labelsInGroup.all { it == labelsInGroup[0] }, "group split across labels: $labelsInGroup")
            assertTrue(labelsInGroup[0] >= 0, "group was called noise")
            labelsInGroup[0]
        }
        assertEquals(3, groupLabels.toSet().size, "the three groups should have distinct labels: $groupLabels")

        // the scattered points (after all three groups) are noise
        for (i in offset until n) assertEquals(-1, labels[i], "point $i should be noise")
    }

    @Test
    fun `fewer points than minClusterSize are all noise`() {
        val points = syntheticPoints(listOf(4), noise = 0)
        val labels = Hdbscan.cluster(distances(points), points.size, minClusterSize = 10)
        assertTrue(labels.all { it == -1 })
    }

    @Test
    fun `a single uniform blob does not crash and keeps labels in range`() {
        val rnd = Random(5)
        val points = Array(60) { DoubleArray(5) { rnd.nextDouble(-1.0, 1.0) } }
        val labels = Hdbscan.cluster(distances(points), points.size, minClusterSize = 5)
        val distinct = labels.filter { it >= 0 }.toSet()
        assertTrue(labels.all { it == -1 || it in 0 until distinct.size })
    }

    @Test
    fun `larger clusters are numbered first`() {
        val points = syntheticPoints(listOf(8, 20), noise = 0)
        val labels = Hdbscan.cluster(distances(points), points.size, minClusterSize = 5)
        val small = labels.take(8).toSet()
        val big = labels.drop(8).toSet()
        assertEquals(1, small.size)
        assertEquals(1, big.size)
        assertTrue(big.first() < small.first(), "the bigger group (20) should get the lower label")
    }
}
