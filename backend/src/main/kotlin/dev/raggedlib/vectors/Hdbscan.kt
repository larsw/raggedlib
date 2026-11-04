package dev.raggedlib.vectors

/**
 * HDBSCAN (Campello, Moulavi & Sander, 2013): density-based clustering that extracts a flat partition from a
 * hierarchy of density-based clusters. Unlike k-means it needs no cluster count, and unlike plain DBSCAN no
 * single density threshold; points that never settle into a cluster are noise (label -1). Pure function, no
 * I/O — [n] is small enough here (at most [VectorSpaceService.MAX_SIZE]) that straightforward O(n²) steps
 * (dense MST, explicit per-point bookkeeping) are simpler to get right than the sparse/approximate tricks real
 * implementations use for millions of points.
 *
 * Clusters are extracted by the "leaf" method (every cluster that never splits further becomes a topic), not
 * the more commonly described "excess of mass" (a cluster is kept only if more stable than its children). EOM
 * favours a *conservative*, small number of broad clusters — appropriate for outlier detection, where it was
 * designed — but on these (sampled, continuous, high-dimensional) embeddings it reliably picks one giant
 * cluster containing almost everything: whichever cluster survives the longest before its first split banks
 * stability (size × lambda range) from birth, and when that first split happens late, no finer sub-cluster
 * — however real — can accumulate enough stability in its shorter remaining lifetime to outweigh it. "Leaf"
 * avoids that pathology and is the standard alternative for exactly this situation (`cluster_selection_method
 * ='leaf'` in the reference `hdbscan` implementation); it also happens to suit topic discovery better, where
 * many specific topics beat one broad one.
 */
object Hdbscan {
    /**
     * Clusters [n] points given their pairwise (real, not squared) distances [dist] (row-major n×n, symmetric,
     * zero diagonal). Returns a label per point: -1 is noise, 0..k-1 are clusters, numbered by descending size.
     * [minClusterSize] is the smallest group of points considered a cluster; [minSamples] (default: the same)
     * sets how conservative the core-distance estimate is — higher is more robust to noise but shrinks clusters.
     */
    fun cluster(dist: FloatArray, n: Int, minClusterSize: Int, minSamples: Int = minClusterSize): IntArray {
        val mcs = minClusterSize.coerceAtLeast(2)
        if (n < mcs) return IntArray(n) { -1 }
        val core = coreDistances(dist, n, minSamples.coerceIn(1, n - 1))
        val mst = minimumSpanningTree(dist, n, core)
        val hierarchy = singleLinkage(mst, n)
        val condensed = condense(hierarchy, n, mcs)
        return extract(condensed, n)
    }

    // ---------------------------------------------------------------------------------------------------------

    private fun coreDistances(dist: FloatArray, n: Int, minSamples: Int): DoubleArray = DoubleArray(n) { i ->
        val row = DoubleArray(n - 1)
        var idx = 0
        for (j in 0 until n) if (j != i) row[idx++] = dist[i * n + j].toDouble()
        row.sort()
        row[minSamples - 1]
    }

    private class Edge(val a: Int, val b: Int, val weight: Double)

    /** Prim's MST (dense; O(n²)) over the mutual-reachability graph: mrd(i,j) = max(core[i], core[j], dist(i,j)). */
    private fun minimumSpanningTree(dist: FloatArray, n: Int, core: DoubleArray): List<Edge> {
        fun mrd(i: Int, j: Int) = maxOf(core[i], core[j], dist[i * n + j].toDouble())
        val inTree = BooleanArray(n)
        val minEdge = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val minFrom = IntArray(n) { 0 }
        inTree[0] = true
        for (j in 1 until n) {
            minEdge[j] = mrd(0, j)
        }
        val edges = ArrayList<Edge>(n - 1)
        repeat(n - 1) {
            var best = -1
            var bestW = Double.POSITIVE_INFINITY
            for (j in 0 until n) if (!inTree[j] && minEdge[j] < bestW) {
                bestW = minEdge[j]
                best = j
            }
            inTree[best] = true
            edges += Edge(minFrom[best], best, bestW)
            for (j in 0 until n) if (!inTree[j]) {
                val w = mrd(best, j)
                if (w < minEdge[j]) {
                    minEdge[j] = w
                    minFrom[j] = best
                }
            }
        }
        return edges
    }

    /** A single-linkage merge tree: points are nodes 0..n-1, merge k is node n+k, combining [childA]\[k]/[childB]\[k]. */
    private class Hierarchy(val childA: IntArray, val childB: IntArray, val dist: DoubleArray, val size: IntArray)

    private fun singleLinkage(edges: List<Edge>, n: Int): Hierarchy {
        val sorted = edges.sortedBy { it.weight }
        val parent = IntArray(n) { it }
        val rank = IntArray(n)
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) {
                val next = parent[c]
                parent[c] = r
                c = next
            }
            return r
        }

        val currentNode = IntArray(n) { it } // current cluster-tree node id representing each DSU root's component
        val sizeByNode = IntArray(2 * n - 1) { if (it < n) 1 else 0 }
        val childA = IntArray(n - 1)
        val childB = IntArray(n - 1)
        val mergeDist = DoubleArray(n - 1)
        for ((k, e) in sorted.withIndex()) {
            val ra = find(e.a)
            val rb = find(e.b)
            val nodeA = currentNode[ra]
            val nodeB = currentNode[rb]
            childA[k] = nodeA
            childB[k] = nodeB
            mergeDist[k] = e.weight
            val size = sizeByNode[nodeA] + sizeByNode[nodeB]
            sizeByNode[n + k] = size
            // union by rank over the DSU roots (always original point indices); track the surviving root's node id
            val newRoot = if (rank[ra] < rank[rb]) {
                parent[ra] = rb; rb
            } else if (rank[ra] > rank[rb]) {
                parent[rb] = ra; ra
            } else {
                parent[rb] = ra; rank[ra]++; ra
            }
            currentNode[newRoot] = n + k
        }
        return Hierarchy(childA, childB, mergeDist, sizeByNode.copyOfRange(n, 2 * n - 1))
    }

    /** A persistent (density-based) cluster: born when its parent splits (or at the root), until it dissolves. */
    private class Condensed(
        val parent: Map<Int, Int>,
        /** Each point's persistent-cluster membership, root to deepest, in order. */
        val path: Array<MutableList<Int>>,
        val root: Int,
    )

    private fun condense(h: Hierarchy, n: Int, minClusterSize: Int): Condensed {
        val parent = HashMap<Int, Int>()
        val path = Array(n) { mutableListOf<Int>() }
        var nextId = 0
        fun newCluster(parentId: Int): Int {
            val id = nextId++
            parent[id] = parentId
            return id
        }

        fun sizeOfNode(node: Int) = if (node < n) 1 else h.size[node - n]
        fun leavesOf(node: Int): List<Int> {
            if (node < n) return listOf(node)
            val out = ArrayList<Int>(sizeOfNode(node))
            val stack = ArrayDeque<Int>()
            stack.addLast(node)
            while (stack.isNotEmpty()) {
                val m = stack.removeLast()
                if (m < n) out += m
                else {
                    val k = m - n
                    stack.addLast(h.childA[k])
                    stack.addLast(h.childB[k])
                }
            }
            return out
        }

        fun close(clusterId: Int, points: List<Int>) {
            for (p in points) path[p].add(clusterId)
        }

        fun process(node: Int, clusterId: Int) {
            if (node < n) {
                close(clusterId, listOf(node))
                return
            }
            val k = node - n
            val a = h.childA[k]
            val b = h.childB[k]
            val bigA = sizeOfNode(a) >= minClusterSize
            val bigB = sizeOfNode(b) >= minClusterSize
            when {
                bigA && bigB -> {
                    close(clusterId, leavesOf(node))
                    process(a, newCluster(clusterId))
                    process(b, newCluster(clusterId))
                }
                bigA -> {
                    close(clusterId, leavesOf(b))
                    process(a, clusterId)
                }
                bigB -> {
                    close(clusterId, leavesOf(a))
                    process(b, clusterId)
                }
                else -> close(clusterId, leavesOf(node))
            }
        }

        val root = n + (n - 2)
        val rootCluster = newCluster(-1)
        process(root, rootCluster)
        return Condensed(parent, path, rootCluster)
    }

    /** "Leaf" selection: every persistent cluster that never split further (no children) becomes a topic. */
    private fun extract(c: Condensed, n: Int): IntArray {
        val hasChildren = HashSet<Int>()
        for (p in c.parent.values) if (p >= 0) hasChildren += p
        val selected = c.parent.keys.filterNot { it in hasChildren }.toSet()

        val rawLabel = IntArray(n) { p -> c.path[p].lastOrNull { it in selected } ?: -1 }
        val sizeByCluster = rawLabel.filter { it >= 0 }.groupingBy { it }.eachCount()
        val ordered = sizeByCluster.entries.sortedByDescending { it.value }.map { it.key }
        val relabel = ordered.withIndex().associate { (i, id) -> id to i }
        return IntArray(n) { p -> relabel[rawLabel[p]] ?: -1 }
    }
}
