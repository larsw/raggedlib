package dev.raggedlib.vectors

import kotlin.test.Test
import kotlin.test.assertEquals

class VectorParamsTest {
    @Test
    fun `a layout keeps only its method's parameters, defaults filled in`() {
        val given = LayoutParams(neighbors = 30, perplexity = 50.0)
        assertEquals(LayoutParams(), given.forMethod(VectorMethod.PCA))
        assertEquals(LayoutParams(neighbors = 30, minDist = 0.1, epochs = 300), given.forMethod(VectorMethod.UMAP))
        assertEquals(LayoutParams(perplexity = 50.0, iterations = 1000), given.forMethod(VectorMethod.TSNE))
        // the spec's default is the method's defaults
        assertEquals(LayoutParams().forMethod(VectorMethod.UMAP), LayoutSpec(VectorLevel.PASSAGES, VectorMethod.UMAP, 10).params)
    }

    @Test
    fun `topic clustering defaults to a size-based minimum and min samples of the same`() {
        assertEquals(25, TopicSpec(VectorLevel.PASSAGES, 1000, ClusterSpace.RAW).resolved().minClusterSize)
        assertEquals(25, TopicSpec(VectorLevel.PASSAGES, 1000, ClusterSpace.RAW).resolved().minSamples)
        assertEquals(5, TopicSpec(VectorLevel.PASSAGES, 50, ClusterSpace.RAW).resolved().minClusterSize)
        assertEquals(50, TopicSpec(VectorLevel.PASSAGES, 2000, ClusterSpace.RAW, minClusterSize = 50).resolved().minSamples)
        val custom = TopicSpec(VectorLevel.PASSAGES, 1000, ClusterSpace.RAW, minClusterSize = 10, minSamples = 3).resolved()
        assertEquals(10 to 3, custom.minClusterSize to custom.minSamples)
        // "auto" and its value are the same spec, so one cache entry
        assertEquals(
            TopicSpec(VectorLevel.PASSAGES, 1000, ClusterSpace.RAW).resolved(),
            TopicSpec(VectorLevel.PASSAGES, 1000, ClusterSpace.RAW, minClusterSize = 25).resolved(),
        )
    }
}
