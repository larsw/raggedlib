package dev.raggedlib.vectors

import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.ingest.ChunkStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

enum class VectorMethod { PCA, UMAP, TSNE }

data class VectorPoint(
    val id: String,
    val bookId: Long,
    val title: String,
    val page: Int?,
    val section: String?,
    val snippet: String,
    val x: Double,
    val y: Double,
)

data class VectorSpace(
    val libraryId: Long,
    val method: VectorMethod,
    val seed: Long,
    /** passages in the sample (at most the requested size) */
    val size: Int,
    /** passages with an embedding in this library */
    val totalPassages: Long,
    val dimensions: Int,
    /** PCA only: the share of total variance of each axis */
    val explainedVariance: List<Double>?,
    /** how long computing it took (also when served from the cache) */
    val elapsedMs: Long,
    val points: List<VectorPoint>,
    /** served from the cache rather than computed for this request */
    val cached: Boolean = false,
)

@Service
class VectorSpaceService(
    private val jdbc: JdbcTemplate,
    private val props: RaggedlibProperties,
) {
    private val cache = ProjectionCache(::compute, ::fingerprint)

    /**
     * The 2-D layout of the library's current sample (or, given a [seed], of that one, which a reshuffle makes the
     * current sample), from the cache while the library's passages are unchanged. See [ProjectionCache].
     */
    fun space(libraryId: Long, method: VectorMethod, size: Int, seed: Long?): VectorSpace =
        cache.get(libraryId, method, size, seed)

    /**
     * Changes whenever passages of the library's books may have: a book indexed or re-indexed (indexed_at, chunk
     * count), losing its passages (no indexed_at), or removed. Cheap: one pass over the books table, not the vectors.
     */
    private fun fingerprint(libraryId: Long): String = jdbc.queryForObject(
        """
        select count(*) || ':' || coalesce(sum(chunk_count), 0) || ':' || count(indexed_at) || ':' || coalesce(max(indexed_at)::text, '')
        from books where library_id = ?
        """.trimIndent(),
        String::class.java, libraryId,
    )!!

    /**
     * A 2-D layout of [size] passages drawn from the vector store, from the books of library [libraryId] only. The
     * sample is the rows with the smallest md5(id, seed), so the same seed gives the same passages and a new seed a
     * new sample.
     */
    private fun compute(libraryId: Long, method: VectorMethod, size: Int, seed: Long): VectorSpace {
        val started = System.nanoTime()
        val total = jdbc.queryForObject(COUNT_SQL, Long::class.java, libraryId) ?: 0L
        val rows = jdbc.query(SAMPLE_SQL, { rs, _ ->
            Row(
                id = rs.getString(1),
                bookId = rs.getLong(2),
                title = rs.getString(3),
                page = rs.getObject(4) as Int?,
                section = rs.getString(5),
                snippet = rs.getString(6) ?: "",
                embedding = ChunkStore.parseVector(rs.getString(7)),
            )
        }, libraryId, seed.toString(), size)

        val n = rows.size
        if (n < MIN_POINTS) {
            return VectorSpace(libraryId, method, seed, n, total, props.embedding.dimensions, null, elapsedMs(started), emptyList())
        }

        val raw = Array(n) { i -> DoubleArray(rows[i].embedding.size) { rows[i].embedding[it].toDouble() } }
        val pca = Projections.pca(raw, seed = seed)
        val coords = when (method) {
            VectorMethod.PCA -> pca.coords
            VectorMethod.TSNE -> {
                val sq = Projections.squaredCosineDistances(Array(n) { rows[it].embedding })
                Projections.tsne(sq, n, pca.coords, perplexity = minOf(30.0, (n - 1) / 3.0))
            }
            VectorMethod.UMAP -> {
                val sq = Projections.squaredCosineDistances(Array(n) { rows[it].embedding })
                Projections.umap(sq, n, pca.coords, seed = seed)
            }
        }

        val points = List(n) { i ->
            val r = rows[i]
            VectorPoint(r.id, r.bookId, r.title, r.page, r.section, r.snippet, coords[i][0], coords[i][1])
        }
        return VectorSpace(
            libraryId = libraryId,
            method = method,
            seed = seed,
            size = n,
            totalPassages = total,
            dimensions = props.embedding.dimensions,
            explainedVariance = if (method == VectorMethod.PCA) pca.explainedVariance.toList() else null,
            elapsedMs = elapsedMs(started),
            points = points,
        )
    }

    private class Row(
        val id: String,
        val bookId: Long,
        val title: String,
        val page: Int?,
        val section: String?,
        val snippet: String,
        val embedding: FloatArray,
    )

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    companion object {
        const val MIN_POINTS = 3
        const val MAX_SIZE = 2000
        private const val SNIPPET_CHARS = 240

        private val COUNT_SQL = """
            select count(*)
            from vector_store v join books b on b.id = v.book_id
            where v.embedding is not null and b.library_id = ?
        """.trimIndent()

        private val SAMPLE_SQL = """
            select v.id::text, v.book_id, b.title, v.page, v.section, left(v.content, $SNIPPET_CHARS), v.embedding::text
            from vector_store v join books b on b.id = v.book_id
            where v.embedding is not null and b.library_id = ?
            order by md5(v.id::text || ?)
            limit ?
        """.trimIndent()
    }
}
