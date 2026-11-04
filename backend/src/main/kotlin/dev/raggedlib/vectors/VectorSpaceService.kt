package dev.raggedlib.vectors

import com.fasterxml.jackson.annotation.JsonValue
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.ingest.ChunkStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import kotlin.math.sqrt

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

/** What to lay out: [size] passages or books, projected by [method] to [dims] (2 or 3) dimensions. */
data class LayoutSpec(val level: VectorLevel, val method: VectorMethod, val size: Int, val dims: Int = 2)

/** Which embedding space topics are clustered in: the raw embeddings, or a UMAP reduction (closer to BERTopic). */
enum class ClusterSpace {
    RAW, UMAP;

    @JsonValue fun json() = name.lowercase()
}

/** What to cluster into topics: [size] passages or books. Independent of the projection [VectorMethod]/dims. */
data class TopicSpec(val level: VectorLevel, val size: Int, val space: ClusterSpace)

/** A discovered topic: [keywords] from class-based TF-IDF over its members' text (see [TopicLabels]). */
data class Topic(val id: Int, val label: String, val keywords: List<String>, val size: Int)

data class TopicSpace(
    val libraryId: Long,
    val level: VectorLevel,
    val seed: Long,
    /** points in the sample that were clustered (at most the requested size) */
    val size: Int,
    /** passages (or books) with a vector in this library: what the sample is drawn from */
    val total: Long,
    val space: ClusterSpace,
    val elapsedMs: Long,
    val topics: List<Topic>,
    /** point id (matching GET /api/vectors' point ids for the same library/level/size/seed) -> topic id, or -1 for noise */
    val pointTopics: Map<String, Int>,
    val cached: Boolean = false,
)

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
    /** 3-D layouts only */
    val z: Double? = null,
)

data class VectorSpace(
    val libraryId: Long,
    val level: VectorLevel,
    val method: VectorMethod,
    /** 2 or 3: the layout's dimensions (points have a z in 3-D) */
    val dims: Int,
    val seed: Long,
    /** points in the sample (at most the requested size) */
    val size: Int,
    /** passages (or books) with a vector in this library: what the sample is drawn from */
    val total: Long,
    /** books of this library with passages but no book vector yet (see POST /api/vectors/book-vectors) */
    val missingBookVectors: Int,
    val dimensions: Int,
    /** PCA only: the share of total variance of each axis (dims of them) */
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
    private val named: NamedParameterJdbcTemplate,
    private val props: RaggedlibProperties,
    private val bookVectors: BookVectors,
) {
    private val cache = SampleCache<LayoutSpec, VectorSpace>(::compute, { it.copy(cached = true) }, ::fingerprint)
    private val topicCache = SampleCache<TopicSpec, TopicSpace>(::computeTopics, { it.copy(cached = true) }, ::fingerprint)

    /**
     * The 2-D layout of the library's current sample (or, given a [seed], of that one, which a reshuffle makes the
     * current sample), from the cache while the library's vectors are unchanged. See [SampleCache].
     */
    fun space(libraryId: Long, spec: LayoutSpec, seed: Long?): VectorSpace = cache.get(libraryId, spec, seed)

    /**
     * HDBSCAN clusters ([Hdbscan]) of the library's current sample at [TopicSpec.level], labelled by class-based
     * TF-IDF ([TopicLabels]) over their members' text. Shares the library's current sample with [space] (same
     * point ids for the same level/size/seed), cached the same way.
     */
    fun topics(libraryId: Long, spec: TopicSpec, seed: Long?): TopicSpace = topicCache.get(libraryId, spec, seed)

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
     * A 2-D or 3-D layout of [LayoutSpec.size] passages (or book vectors) of library [libraryId]'s books. The sample
     * is the rows with the smallest md5(id, seed), so the same seed gives the same points and a new seed a new sample.
     */
    private fun compute(libraryId: Long, spec: LayoutSpec, seed: Long): VectorSpace {
        val (level, method, size, dims) = spec
        val started = System.nanoTime()
        val total = countTotal(level, libraryId)
        val missing = bookVectors.countMissing(libraryId)
        val rows = sampleRows(level, libraryId, size, seed)

        val n = rows.size
        if (n < MIN_POINTS) {
            return VectorSpace(
                libraryId, level, method, dims, seed, n, total, missing, props.embedding.dimensions, null,
                elapsedMs(started), emptyList(),
            )
        }

        val raw = Array(n) { i -> DoubleArray(rows[i].embedding.size) { rows[i].embedding[it].toDouble() } }
        val pca = Projections.pca(raw, components = dims, seed = seed)
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
            VectorPoint(
                r.id, r.bookId, r.title, r.page, r.section, r.snippet, coords[i][0], coords[i][1],
                if (dims == 3) coords[i][2] else null,
            )
        }
        return VectorSpace(
            libraryId = libraryId,
            level = level,
            method = method,
            dims = dims,
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

    /** HDBSCAN clusters of the library's current sample at [TopicSpec.level], labelled by c-TF-IDF over their text. */
    private fun computeTopics(libraryId: Long, spec: TopicSpec, seed: Long): TopicSpace {
        val started = System.nanoTime()
        val total = countTotal(spec.level, libraryId)
        val rows = sampleRows(spec.level, libraryId, spec.size, seed)
        val n = rows.size
        if (n < MIN_POINTS) {
            return TopicSpace(libraryId, spec.level, seed, n, total, spec.space, elapsedMs(started), emptyList(), emptyMap())
        }

        val raw = Array(n) { i -> DoubleArray(rows[i].embedding.size) { rows[i].embedding[it].toDouble() } }
        val dist = when (spec.space) {
            ClusterSpace.RAW -> {
                val sq = Projections.squaredCosineDistances(Array(n) { rows[it].embedding })
                FloatArray(sq.size) { sqrt(sq[it]) }
            }
            ClusterSpace.UMAP -> {
                val reduceDims = minOf(2, n - 1, raw[0].size)
                val pcaInit = Projections.pca(raw, components = reduceDims, seed = seed)
                val sq = Projections.squaredCosineDistances(Array(n) { rows[it].embedding })
                val reduced = Projections.umap(sq, n, pcaInit.coords, seed = seed)
                val sqE = Projections.squaredEuclideanDistances(reduced)
                FloatArray(sqE.size) { sqrt(sqE[it]) }
            }
        }
        val minClusterSize = (spec.size / 40).coerceIn(5, 50)
        val labels = Hdbscan.cluster(dist, n, minClusterSize)

        val wordCounts = wordCountsByCluster(spec.level, rows, labels)
        val keywords = TopicLabels.label(wordCounts)
        val sizeByCluster = labels.filter { it >= 0 }.groupingBy { it }.eachCount()
        val topics = sizeByCluster.entries.sortedByDescending { it.value }.map { (id, clusterSize) ->
            val kw = keywords[id].orEmpty()
            Topic(id, kw.joinToString(", ").ifEmpty { "Topic $id" }, kw, clusterSize)
        }
        val pointTopics = rows.indices.associate { i -> rows[i].id to labels[i] }
        return TopicSpace(libraryId, spec.level, seed, n, total, spec.space, elapsedMs(started), topics, pointTopics)
    }

    /**
     * Word counts per cluster, from Postgres's own tokenisation: `content_tsv` (passages) is already stemmed and
     * stopword-filtered (V1__init.sql); book titles are tokenised the same way on the fly, cheap at this size.
     */
    private fun wordCountsByCluster(level: VectorLevel, rows: List<Row>, labels: IntArray): Map<Int, Map<String, Int>> {
        val clusterOf = HashMap<String, Int>()
        for (i in rows.indices) if (labels[i] >= 0) clusterOf[rows[i].id] = labels[i]
        if (clusterOf.isEmpty()) return emptyMap()
        val sql = if (level == VectorLevel.PASSAGES) WORD_COUNT_PASSAGES_SQL else WORD_COUNT_BOOKS_SQL
        val counts = HashMap<Int, HashMap<String, Int>>()
        named.query(sql, MapSqlParameterSource("ids", clusterOf.keys.toList())) { rs ->
            val cluster = clusterOf[rs.getString(1)] ?: return@query
            val word = rs.getString(2) ?: return@query
            counts.getOrPut(cluster) { HashMap() }.merge(word, 1, Int::plus)
        }
        return counts
    }

    private fun sampleRows(level: VectorLevel, libraryId: Long, size: Int, seed: Long): List<Row> {
        val sampleSql = when (level) {
            VectorLevel.PASSAGES -> PASSAGE_SAMPLE_SQL
            VectorLevel.BOOKS -> BOOK_SAMPLE_SQL
        }
        return jdbc.query(sampleSql, { rs, _ ->
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
    }

    private fun countTotal(level: VectorLevel, libraryId: Long): Long {
        val countSql = when (level) {
            VectorLevel.PASSAGES -> PASSAGE_COUNT_SQL
            VectorLevel.BOOKS -> BOOK_COUNT_SQL
        }
        return jdbc.queryForObject(countSql, Long::class.java, libraryId) ?: 0L
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

        private val WORD_COUNT_PASSAGES_SQL = """
            select v.id::text, unnest(tsvector_to_array(v.content_tsv)) from vector_store v where v.id::text in (:ids)
        """.trimIndent()

        private val WORD_COUNT_BOOKS_SQL = """
            select b.id::text, unnest(tsvector_to_array(to_tsvector('english', b.title))) from books b where b.id::text in (:ids)
        """.trimIndent()
    }
}
