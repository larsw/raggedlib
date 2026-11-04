package dev.raggedlib.vectors

import com.fasterxml.jackson.annotation.JsonValue
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.ingest.ChunkStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/** Lower case in the API ("pca", "umap", "tsne"), as the requests spell it. */
enum class VectorMethod {
    PCA, UMAP, TSNE;

    @JsonValue fun json() = name.lowercase()
}

/** What a point stands for: one passage, or one book (the average of its passages, see [BookVectors]). */
enum class VectorLevel {
    PASSAGES, BOOKS;

    @JsonValue fun json() = name.lowercase()
}

data class VectorPoint(
    /** the passage's id, or (books) the book's */
    val id: String,
    val bookId: Long,
    val title: String,
    val page: Int?,
    /** the passage's section; for a book, how many passages its vector averages */
    val section: String?,
    /** the passage's leading text; for a book, its authors, publisher and year */
    val snippet: String,
    val x: Double,
    val y: Double,
)

data class VectorSpace(
    val libraryId: Long,
    val level: VectorLevel,
    val method: VectorMethod,
    val seed: Long,
    /** points in the sample (at most the requested size) */
    val size: Int,
    /** passages (or books) with a vector in this library: what the sample is drawn from */
    val total: Long,
    /** books of this library with passages but no book vector yet (see POST /api/vectors/book-vectors) */
    val missingBookVectors: Int,
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
    private val bookVectors: BookVectors,
) {
    private val cache = ProjectionCache(::compute, ::fingerprint)

    /**
     * The 2-D layout of the library's current sample (or, given a [seed], of that one, which a reshuffle makes the
     * current sample), from the cache while the library's vectors are unchanged. See [ProjectionCache].
     */
    fun space(libraryId: Long, level: VectorLevel, method: VectorMethod, size: Int, seed: Long?): VectorSpace =
        cache.get(libraryId, level, method, size, seed)

    /** Computes the missing (or outdated) book vectors of [libraryId]; returns how many. */
    fun computeBookVectors(libraryId: Long?): Int = bookVectors.computeMissing(libraryId)

    fun missingBookVectors(libraryId: Long?): Int = bookVectors.countMissing(libraryId)

    /**
     * Changes whenever the library's passages may have (a book indexed or re-indexed: indexed_at, chunk count;
     * losing its passages: no indexed_at; removed) or its book vectors (computed). Cheap: no pass over the passages.
     */
    private fun fingerprint(libraryId: Long): String = jdbc.queryForObject(
        """
        select count(*) || ':' || coalesce(sum(chunk_count), 0) || ':' || count(indexed_at) || ':' ||
               coalesce(max(indexed_at)::text, '') || ':' ||
               (select count(*) || ':' || coalesce(max(bv.computed_at)::text, '')
                from book_vectors bv join books vb on vb.id = bv.book_id where vb.library_id = ?)
        from books where library_id = ?
        """.trimIndent(),
        String::class.java, libraryId, libraryId,
    )!!

    /**
     * A 2-D layout of [size] passages (or book vectors) of library [libraryId]'s books. The sample is the rows with
     * the smallest md5(id, seed), so the same seed gives the same points and a new seed a new sample.
     */
    private fun compute(libraryId: Long, level: VectorLevel, method: VectorMethod, size: Int, seed: Long): VectorSpace {
        val started = System.nanoTime()
        val (countSql, sampleSql) = when (level) {
            VectorLevel.PASSAGES -> PASSAGE_COUNT_SQL to PASSAGE_SAMPLE_SQL
            VectorLevel.BOOKS -> BOOK_COUNT_SQL to BOOK_SAMPLE_SQL
        }
        val total = jdbc.queryForObject(countSql, Long::class.java, libraryId) ?: 0L
        val missing = bookVectors.countMissing(libraryId)
        val rows = jdbc.query(sampleSql, { rs, _ ->
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
            return VectorSpace(
                libraryId, level, method, seed, n, total, missing, props.embedding.dimensions, null,
                elapsedMs(started), emptyList(),
            )
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
            level = level,
            method = method,
            seed = seed,
            size = n,
            total = total,
            missingBookVectors = missing,
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

        private val PASSAGE_COUNT_SQL = """
            select count(*)
            from vector_store v join books b on b.id = v.book_id
            where v.embedding is not null and b.library_id = ?
        """.trimIndent()

        private val PASSAGE_SAMPLE_SQL = """
            select v.id::text, v.book_id, b.title, v.page, v.section, left(v.content, $SNIPPET_CHARS), v.embedding::text
            from vector_store v join books b on b.id = v.book_id
            where v.embedding is not null and b.library_id = ?
            order by md5(v.id::text || ?)
            limit ?
        """.trimIndent()

        private val BOOK_COUNT_SQL = """
            select count(*) from book_vectors bv join books b on b.id = bv.book_id where b.library_id = ?
        """.trimIndent()

        private val BOOK_SAMPLE_SQL = """
            select b.id::text, b.id, b.title, null::int, bv.passages || ' passages',
                   concat_ws(' · ', nullif(array_to_string(b.authors, ', '), ''), b.publisher,
                             extract(year from b.pubdate)::int::text),
                   bv.embedding::text
            from book_vectors bv join books b on b.id = bv.book_id
            where b.library_id = ?
            order by md5(b.id::text || ?)
            limit ?
        """.trimIndent()
    }
}
