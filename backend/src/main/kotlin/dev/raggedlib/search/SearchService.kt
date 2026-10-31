package dev.raggedlib.search

import com.pgvector.PGvector
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.config.badRequest
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.Executors

/** Hybrid retrieval: pgvector cosine top-N + Postgres full-text top-N, fused with RRF. */
@Service
class SearchService(
    private val props: RaggedlibProperties,
    private val jdbc: NamedParameterJdbcTemplate,
    private val embeddingModel: EmbeddingModel,
    private val tx: TransactionTemplate,
) {
    private data class Row(val id: String, val bookId: Long, val title: String, val authors: List<String>,
                           val page: Int?, val section: String?, val text: String)

    fun search(req: SearchRequest): List<SourceChunk> {
        val query = req.query.trim()
        if (query.isEmpty()) badRequest("query must not be empty")
        val s = props.search
        val topK = (req.topK ?: s.defaultTopK).coerceIn(1, s.maxTopK)
        val bookIds = req.bookIds?.distinct()?.takeIf { it.isNotEmpty() }
        val tags = req.tags?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }

        val filters = mutableListOf<String>()
        val params = MapSqlParameterSource()
        if (bookIds != null) {
            filters += "v.book_id in (:bookIds)"
            params.addValue("bookIds", bookIds)
        }
        if (tags != null) {
            filters += "v.book_id in (select id from books where tags && cast(:tags as text[]))"
            params.addValue("tags", tags.toTypedArray())
        }
        val filterSql = filters.joinToString("") { " and $it" }

        // embed the query and run full-text search concurrently
        val (vectorRows, textRows) = Executors.newVirtualThreadPerTaskExecutor().use { exec ->
            val text = exec.submit<List<Row>> { textSearch(query, filterSql, params, s.textCandidates) }
            val vector = exec.submit<List<Row>> {
                val emb = embeddingModel.embed(props.embedding.queryInstruction + query)
                vectorSearch(emb, filterSql, params, s.vectorCandidates)
            }
            vector.get() to text.get()
        }

        val byId = HashMap<String, Row>()
        (vectorRows + textRows).forEach { byId.putIfAbsent(it.id, it) }
        val fused = HybridRanking.rrf(
            listOf(vectorRows, textRows).map { list -> list.map { HybridRanking.Candidate(it.id, it.bookId) } },
            s.rrfK,
        )
        val cap = if (bookIds != null && bookIds.size <= 2) null else s.maxChunksPerBook
        return HybridRanking.capPerBook(fused, cap, topK).mapIndexed { i, f ->
            val r = byId.getValue(f.id)
            SourceChunk(i + 1, r.id, r.bookId, r.title, r.authors, r.page, r.section, r.text, Math.round(f.score * 1e6) / 1e6)
        }
    }

    private fun vectorSearch(embedding: FloatArray, filterSql: String, base: MapSqlParameterSource, limit: Int): List<Row> {
        val params = MapSqlParameterSource(base.values).addValue("emb", PGvector(embedding)).addValue("limit", limit)
        return tx.execute {
            jdbc.jdbcTemplate.execute("set local hnsw.ef_search = ${props.search.hnswEfSearch.coerceIn(10, 1000)}")
            // pgvector >= 0.8: keep scanning the HNSW graph when filters remove candidates
            jdbc.jdbcTemplate.execute("set local hnsw.iterative_scan = relaxed_order")
            jdbc.query(
                """
                select * from (
                    select v.id, v.book_id, b.title, b.authors, v.page, v.section, v.content,
                           v.embedding <=> :emb as distance
                    from vector_store v join books b on b.id = v.book_id
                    where true $filterSql
                    order by v.embedding <=> :emb
                    limit :limit
                ) t order by distance
                """.trimIndent(),
                params,
            ) { rs, _ -> row(rs) }
        } ?: emptyList()
    }

    /**
     * Strict pass first (all terms, websearch syntax). Natural-language questions rarely match every
     * term, so if that yields too few hits, fall back to an OR over the (stop-word-filtered) terms,
     * ranked by ts_rank_cd so passages matching more terms win.
     */
    private fun textSearch(query: String, filterSql: String, base: MapSqlParameterSource, limit: Int): List<Row> {
        val strict = textSearch(query, filterSql, base, limit, "websearch_to_tsquery('english', :q)")
        if (strict.size >= limit / 2) return strict
        val loose = textSearch(
            query, filterSql, base, limit,
            "replace(plainto_tsquery('english', :q)::text, ' & ', ' | ')::tsquery",
        )
        val seen = strict.mapTo(HashSet()) { it.id }
        return (strict + loose.filter { it.id !in seen }).take(limit)
    }

    private fun textSearch(
        query: String, filterSql: String, base: MapSqlParameterSource, limit: Int, tsQueryExpr: String,
    ): List<Row> {
        val params = MapSqlParameterSource(base.values).addValue("q", query).addValue("limit", limit)
        return jdbc.query(
            """
            select v.id, v.book_id, b.title, b.authors, v.page, v.section, v.content,
                   ts_rank_cd(v.content_tsv, q.query) as rank
            from vector_store v join books b on b.id = v.book_id,
                 (select $tsQueryExpr as query) q
            where v.content_tsv @@ q.query $filterSql
            order by rank desc
            limit :limit
            """.trimIndent(),
            params,
        ) { rs, _ -> row(rs) }
    }

    private fun row(rs: ResultSet): Row {
        @Suppress("UNCHECKED_CAST")
        val authors = (rs.getArray("authors")?.array as Array<String>?)?.toList() ?: emptyList()
        return Row(
            id = (rs.getObject("id") as UUID).toString(),
            bookId = rs.getLong("book_id"),
            title = rs.getString("title"),
            authors = authors,
            page = rs.getObject("page") as Int?,
            section = rs.getString("section"),
            text = rs.getString("content"),
        )
    }
}
