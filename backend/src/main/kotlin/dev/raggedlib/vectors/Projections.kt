package dev.raggedlib.vectors

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 2-D projections of embeddings. Pure functions, no I/O.
 *
 * t-SNE and UMAP work on cosine geometry: [squaredCosineDistances] of the L2-normalised rows, where the cosine
 * distance is half the squared Euclidean distance. The matrix is row-major, n × n.
 */
object Projections {
    class Pca(val coords: Array<DoubleArray>, val explainedVariance: DoubleArray)

    /** Principal components of [x] (rows are points, not normalised), with the share of variance each explains. */
    fun pca(x: Array<DoubleArray>, components: Int = 2, iterations: Int = 100, seed: Long = 0): Pca {
        val n = x.size
        val d = x[0].size
        val mean = DoubleArray(d)
        for (row in x) for (j in 0 until d) mean[j] += row[j] / n
        val xc = Array(n) { i -> DoubleArray(d) { j -> x[i][j] - mean[j] } }
        val total = xc.sumOf { row -> row.sumOf { it * it } } / n

        val rnd = Random(seed)
        val v = Array(components) { DoubleArray(d) { rnd.nextDouble(-1.0, 1.0) } }
        orthonormalize(v)
        // orthogonal iteration: v ← orth(Xᵀ X v)
        repeat(iterations) {
            val w = Array(components) { DoubleArray(d) }
            for (row in xc) for (c in 0 until components) {
                val z = dot(row, v[c])
                val wc = w[c]
                for (j in 0 until d) wc[j] += z * row[j]
            }
            orthonormalize(w)
            for (c in 0 until components) v[c] = w[c]
        }

        val coords = Array(n) { i -> DoubleArray(components) { c -> dot(xc[i], v[c]) } }
        val variance = DoubleArray(components) { c -> coords.sumOf { it[c] * it[c] } / n }
        val share = DoubleArray(components) { c -> if (total > 0) variance[c] / total else 0.0 }
        return Pca(coords, share)
    }

    /** Row-major n × n matrix of squared Euclidean distances between the L2-normalised rows. */
    fun squaredCosineDistances(rows: Array<FloatArray>): FloatArray {
        val n = rows.size
        val d = rows[0].size
        val unit = Array(n) { i ->
            val norm = sqrt(rows[i].sumOf { it.toDouble() * it }).takeIf { it > 0 } ?: 1.0
            DoubleArray(d) { rows[i][it] / norm }
        }
        val out = FloatArray(n * n)
        for (i in 0 until n) for (j in i + 1 until n) {
            val dist = max(0.0, 2.0 - 2.0 * dot(unit[i], unit[j])).toFloat()
            out[i * n + j] = dist
            out[j * n + i] = dist
        }
        return out
    }

    /**
     * Exact t-SNE (van der Maaten & Hinton, 2008) with early exaggeration, momentum and adaptive gains, as in
     * scikit-learn. [sqDist] are the squared distances the affinities are built from, [init] the starting layout.
     */
    fun tsne(
        sqDist: FloatArray,
        n: Int,
        init: Array<DoubleArray>,
        perplexity: Double = 30.0,
        iterations: Int = 1000,
    ): Array<DoubleArray> {
        val p = symmetricAffinities(sqDist, n, perplexity)
        val xs = DoubleArray(n) { init[it][0] }
        val ys = DoubleArray(n) { init[it][1] }
        scaleTo(xs, ys, 1e-4)
        val learningRate = max(n / 48.0, 50.0)
        val update = DoubleArray(2 * n)
        val gains = DoubleArray(2 * n) { 1.0 }
        val grad = DoubleArray(2 * n)
        val exaggerationIterations = 250

        for (iter in 0 until iterations) {
            val exaggeration = if (iter < exaggerationIterations) 12.0 else 1.0
            val momentum = if (iter < exaggerationIterations) 0.5 else 0.8

            var z = 0.0
            for (i in 0 until n) for (j in i + 1 until n) {
                val dx = xs[i] - xs[j]
                val dy = ys[i] - ys[j]
                z += 2.0 / (1.0 + dx * dx + dy * dy)
            }

            for (i in 0 until n) {
                var gx = 0.0
                var gy = 0.0
                for (j in 0 until n) {
                    if (j == i) continue
                    val dx = xs[i] - xs[j]
                    val dy = ys[i] - ys[j]
                    val num = 1.0 / (1.0 + dx * dx + dy * dy)
                    val coeff = (exaggeration * p[i * n + j] - num / z) * num
                    gx += coeff * dx
                    gy += coeff * dy
                }
                grad[2 * i] = 4.0 * gx
                grad[2 * i + 1] = 4.0 * gy
            }

            for (k in 0 until 2 * n) {
                gains[k] = if ((grad[k] > 0) != (update[k] > 0)) gains[k] + 0.2 else gains[k] * 0.8
                if (gains[k] < 0.01) gains[k] = 0.01
                update[k] = momentum * update[k] - learningRate * gains[k] * grad[k]
            }
            for (i in 0 until n) {
                xs[i] += update[2 * i]
                ys[i] += update[2 * i + 1]
            }
            centre(xs, ys)
        }
        return Array(n) { doubleArrayOf(xs[it], ys[it]) }
    }

    /**
     * UMAP (McInnes et al., 2018): fuzzy kNN graph with cosine distances, symmetrised, then stochastic gradient
     * descent with negative sampling, as in umap-learn with min_dist 0.1, spread 1. [init] is the starting layout.
     */
    fun umap(
        sqDist: FloatArray,
        n: Int,
        init: Array<DoubleArray>,
        neighbors: Int = 15,
        epochs: Int = 300,
        seed: Long = 0,
    ): Array<DoubleArray> {
        val k = min(neighbors, n - 1)
        val edges = fuzzyEdges(sqDist, n, k)
        val rnd = Random(seed)

        val y = Array(n) { i -> DoubleArray(2) { c -> init[i][c] } }
        val scale = y.maxOf { row -> row.maxOf { abs(it) } }.takeIf { it > 0 } ?: 1.0
        for (row in y) for (c in 0..1) row[c] = row[c] / scale * 10.0 + rnd.nextDouble(-1e-4, 1e-4)

        val maxWeight = edges.maxOf { it.weight }
        val kept = edges.filter { it.weight >= maxWeight / epochs }
        val epochsPerSample = DoubleArray(kept.size) { maxWeight / kept[it].weight }
        val epochsPerNegative = DoubleArray(kept.size) { epochsPerSample[it] / NEGATIVE_SAMPLE_RATE }
        val nextSample = epochsPerSample.copyOf()
        val nextNegative = epochsPerNegative.copyOf()

        for (epoch in 0 until epochs) {
            val alpha = 1.0 - epoch.toDouble() / epochs
            for (e in kept.indices) {
                if (nextSample[e] > epoch) continue
                val i = kept[e].a
                val j = kept[e].b
                attract(y, i, j, alpha)
                nextSample[e] += epochsPerSample[e]

                if (epoch >= nextNegative[e]) {
                    val negatives = ((epoch - nextNegative[e]) / epochsPerNegative[e]).toInt()
                    repeat(negatives) {
                        val other = rnd.nextInt(n)
                        if (other != i) repel(y, i, other, alpha)
                    }
                    nextNegative[e] += negatives * epochsPerNegative[e]
                }
            }
        }
        return y
    }

    private class Edge(val a: Int, val b: Int, val weight: Double)

    /** Symmetrised fuzzy-set memberships of the k nearest neighbours; each unordered pair appears once. */
    private fun fuzzyEdges(sqDist: FloatArray, n: Int, k: Int): List<Edge> {
        val directed = HashMap<Long, Double>()
        val target = ln(k.toDouble()) / ln(2.0)
        for (i in 0 until n) {
            val nearest = nearestNeighbours(sqDist, n, i, k)
            val dist = DoubleArray(k) { sqDist[i * n + nearest[it]] / 2.0 } // cosine distance
            val rho = dist.firstOrNull { it > 0 } ?: 0.0
            val sigma = smoothingScale(dist, rho, target)
            for (t in 0 until k) {
                directed[i.toLong() * n + nearest[t]] = exp(-max(0.0, dist[t] - rho) / sigma)
            }
        }
        val out = ArrayList<Edge>(directed.size)
        for ((key, w) in directed) {
            val a = (key / n).toInt()
            val b = (key % n).toInt()
            val reverse = directed[b.toLong() * n + a]
            if (a > b && reverse != null) continue // the pair is emitted from its lower-index entry
            val other = reverse ?: 0.0
            out += Edge(a, b, w + other - w * other)
        }
        return out
    }

    /** The k nearest other points to [i], closest first. */
    private fun nearestNeighbours(sqDist: FloatArray, n: Int, i: Int, k: Int): IntArray {
        val idx = IntArray(k)
        val d = FloatArray(k) { Float.MAX_VALUE }
        var size = 0
        for (j in 0 until n) {
            if (j == i) continue
            val v = sqDist[i * n + j]
            if (size == k && v >= d[k - 1]) continue
            var pos = if (size < k) size++ else k - 1
            while (pos > 0 && d[pos - 1] > v) {
                d[pos] = d[pos - 1]
                idx[pos] = idx[pos - 1]
                pos--
            }
            d[pos] = v
            idx[pos] = j
        }
        return idx
    }

    /** Binary search for sigma with Σ exp(-(d - rho) / sigma) = log2(k), as umap-learn's smooth_knn_dist. */
    private fun smoothingScale(dist: DoubleArray, rho: Double, target: Double): Double {
        var lo = 0.0
        var hi = Double.POSITIVE_INFINITY
        var mid = 1.0
        repeat(64) {
            var sum = 0.0
            for (d in dist) sum += if (d - rho > 0) exp(-(d - rho) / mid) else 1.0
            if (abs(sum - target) < 1e-5) return mid
            if (sum > target) {
                hi = mid
                mid = (lo + hi) / 2
            } else {
                lo = mid
                mid = if (hi == Double.POSITIVE_INFINITY) mid * 2 else (lo + hi) / 2
            }
        }
        return mid
    }

    private fun attract(y: Array<DoubleArray>, i: Int, j: Int, alpha: Double) {
        val dx = y[i][0] - y[j][0]
        val dy = y[i][1] - y[j][1]
        val d2 = dx * dx + dy * dy
        if (d2 <= 0) return
        val coeff = -2.0 * UMAP_A * UMAP_B * d2.pow(UMAP_B - 1) / (UMAP_A * d2.pow(UMAP_B) + 1)
        for (c in 0..1) {
            val grad = clip(coeff * (y[i][c] - y[j][c]))
            y[i][c] += alpha * grad
            y[j][c] -= alpha * grad
        }
    }

    private fun repel(y: Array<DoubleArray>, i: Int, j: Int, alpha: Double) {
        val dx = y[i][0] - y[j][0]
        val dy = y[i][1] - y[j][1]
        val d2 = dx * dx + dy * dy
        val coeff = if (d2 > 0) 2.0 * UMAP_GAMMA * UMAP_B / ((0.001 + d2) * (UMAP_A * d2.pow(UMAP_B) + 1)) else 0.0
        for (c in 0..1) {
            val grad = if (coeff > 0) clip(coeff * (y[i][c] - y[j][c])) else 4.0
            y[i][c] += alpha * grad
        }
    }

    // a, b of the curve 1 / (1 + a x^(2b)) fitted to min_dist = 0.1, spread = 1 (umap-learn's find_ab_params)
    private const val UMAP_A = 1.5769434
    private const val UMAP_B = 0.8950608
    private const val UMAP_GAMMA = 1.0
    private const val NEGATIVE_SAMPLE_RATE = 5
    private fun clip(v: Double): Double = v.coerceIn(-4.0, 4.0)

    /** Joint probabilities P, symmetrised and normalised to sum to 1 (row-major n × n). */
    private fun symmetricAffinities(sqDist: FloatArray, n: Int, perplexity: Double): DoubleArray {
        val target = ln(perplexity)
        val cond = DoubleArray(n * n)
        for (i in 0 until n) {
            var nearest = Double.POSITIVE_INFINITY
            for (j in 0 until n) if (j != i) nearest = min(nearest, sqDist[i * n + j].toDouble())
            var beta = 1.0
            var lo = 0.0
            var hi = Double.POSITIVE_INFINITY
            repeat(100) {
                var sum = 0.0
                var weighted = 0.0
                for (j in 0 until n) {
                    if (j == i) continue
                    // shifting the distances by `nearest` leaves the probabilities unchanged, and avoids underflow
                    val d = sqDist[i * n + j] - nearest
                    val pj = exp(-beta * d)
                    cond[i * n + j] = pj
                    sum += pj
                    weighted += d * pj
                }
                val entropy = ln(sum) + beta * weighted / sum
                if (abs(entropy - target) < 1e-5) return@repeat
                if (entropy > target) {
                    lo = beta
                    beta = if (hi == Double.POSITIVE_INFINITY) beta * 2 else (beta + hi) / 2
                } else {
                    hi = beta
                    beta = (beta + lo) / 2
                }
            }
            var sum = 0.0
            for (j in 0 until n) sum += cond[i * n + j]
            if (sum > 0) for (j in 0 until n) cond[i * n + j] /= sum
        }
        val p = DoubleArray(n * n)
        val norm = 2.0 * n
        for (i in 0 until n) for (j in 0 until n) {
            if (i == j) continue
            p[i * n + j] = max((cond[i * n + j] + cond[j * n + i]) / norm, 1e-12)
        }
        return p
    }

    private fun scaleTo(xs: DoubleArray, ys: DoubleArray, std: Double) {
        val mean = xs.average()
        val sd = sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size).takeIf { it > 0 } ?: 1.0
        for (i in xs.indices) {
            xs[i] = xs[i] / sd * std
            ys[i] = ys[i] / sd * std
        }
    }

    private fun centre(xs: DoubleArray, ys: DoubleArray) {
        val mx = xs.average()
        val my = ys.average()
        for (i in xs.indices) {
            xs[i] -= mx
            ys[i] -= my
        }
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Modified Gram-Schmidt, in place. A vector that collapses to zero is left at zero. */
    private fun orthonormalize(v: Array<DoubleArray>) {
        for (c in v.indices) {
            for (p in 0 until c) {
                val proj = dot(v[c], v[p])
                for (j in v[c].indices) v[c][j] -= proj * v[p][j]
            }
            val norm = sqrt(dot(v[c], v[c]))
            if (norm > 0) for (j in v[c].indices) v[c][j] /= norm
        }
    }
}
