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

/**
 * Hybrid retrieval, fused with weighted RRF over up to three ranked lists:
 *  1. pgvector cosine top-N — each hit weighted by its own similarity, so a weak vector side (the embedding
 *     misreads the query) can't outvote a strong keyword match just by holding rank 1,
 *  2. strict full-text top-N (all terms, websearch syntax) — weight 1,
 *  3. a keyword fallback when (2) finds too little — weighted by term coverage, see [KeywordFallback].
 * The best fused candidates are then re-scored by a cross-encoder ([Reranker]) when one is available.
 */
@Service
class SearchService(
    private val props: RaggedlibProperties,
    private val jdbc: NamedParameterJdbcTemplate,
    private val embeddingModel: EmbeddingModel,
    private val tx: TransactionTemplate,
    private val termStats: TermStatistics,
    private val reranker: Reranker,
) {
    /** [weight] scales the row's RRF contribution (see [HybridRanking.rrf]). */
    private data class Row(val id: String, val bookId: Long, val title: String, val authors: List<String>,
                           val page: Int?, val section: String?, val text: String, val weight: Double = 1.0)

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
        val (vectorRows, textLists) = Executors.newVirtualThreadPerTaskExecutor().use { exec ->
            // full-text cost grows with query length (a pasted page took minutes); the embedding gets it all
            val keywordQuery = query.take(s.maxKeywordQueryChars)
            val text = exec.submit<List<List<Row>>> { textSearch(keywordQuery, filterSql, params, s.textCandidates) }
            val vector = exec.submit<List<Row>> {
                val emb = embeddingModel.embed(props.embedding.queryInstruction + query)
                vectorSearch(emb, filterSql, params, s.vectorCandidates)
            }
            vector.get() to text.get()
        }

        val byId = HashMap<String, Row>()
        val lists = listOf(vectorRows) + textLists
        lists.forEach { list -> list.forEach { byId.putIfAbsent(it.id, it) } }
        val fused = HybridRanking.rrf(
            lists.map { list -> list.map { HybridRanking.Candidate(it.id, it.bookId, it.weight) } },
            s.rrfK,
        )
        val cap = if (bookIds != null && bookIds.size <= 2) null else s.maxChunksPerBook
        val kept = HybridRanking.dropWeak(fused, s.minRelativeScore)
        val reranked = if (req.rerank != false && reranker.enabled) rerank(query, kept, byId, topK) else null
        val scoreKind = if (reranked != null) ScoreKind.RERANK else ScoreKind.FUSED
        return HybridRanking.capPerBook(reranked ?: kept, cap, topK).mapIndexed { i, f ->
            val r = byId.getValue(f.id)
            SourceChunk(i + 1, r.id, r.bookId, r.title, r.authors, r.page, r.section, r.text,
                Math.round(f.score * 1e6) / 1e6, scoreKind)
        }
    }

    /**
     * Cross-encoder pass over the best fused candidates (see [Reranker]): new order, reranker scores, and an
     * absolute cutoff. Candidates beyond the reranked head are dropped — the fused tail has never held anything
     * the reranker would rank above it in evaluation. Null if the reranker is unavailable.
     */
    private fun rerank(query: String, kept: List<HybridRanking.Fused>, byId: Map<String, Row>, topK: Int): List<HybridRanking.Fused>? {
        val r = props.rerank
        val head = kept.take(maxOf(r.candidates, topK))
        if (head.isEmpty()) return head
        val documents = head.map { f ->
            val row = byId.getValue(f.id)
            // the same text the reranker was evaluated with: where the passage comes from, then the passage
            buildString {
                append(row.title)
                if (!row.section.isNullOrBlank()) append(" — ").append(row.section)
                append('\n').append(row.text.take(r.maxDocumentChars))
            }
        }
        val scores = reranker.score(query.take(r.maxQueryChars), documents) ?: return null
        return HybridRanking.rerank(head, scores, r.minScore)
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
            ) { rs, _ ->
                val similarity = 1.0 - rs.getDouble("distance")
                row(rs).copy(weight = HybridRanking.similarityWeight(similarity, props.search.vectorSimFloor, props.search.vectorSimFull))
            }
        } ?: emptyList()
    }

    /**
     * Strict pass (every term, websearch syntax) and — if it finds fewer than half the wanted candidates —
     * the keyword fallback as a separate list, so RRF treats it as its own (weaker) evidence instead of
     * letting it continue the strict ranking.
     */
    private fun textSearch(query: String, filterSql: String, base: MapSqlParameterSource, limit: Int): List<List<Row>> {
        val strict = strictSearch(query, filterSql, base, limit)
        if (strict.size >= limit / 2 || KeywordFallback.usesWebsearchSyntax(query)) return listOf(strict)
        val seen = strict.mapTo(HashSet()) { it.id }
        val fallback = fallbackSearch(query, filterSql, base, limit).filter { it.id !in seen }
        return listOf(strict, fallback)
    }

    private fun strictSearch(query: String, filterSql: String, base: MapSqlParameterSource, limit: Int): List<Row> {
        val params = MapSqlParameterSource(base.values).addValue("q", query).addValue("limit", limit)
        return jdbc.query(
            """
            select v.id, v.book_id, b.title, b.authors, v.page, v.section, v.content,
                   ts_rank_cd(v.content_tsv, q.query) as rank
            from vector_store v join books b on b.id = v.book_id,
                 websearch_to_tsquery('english', :q) q(query)
            where v.content_tsv @@ q.query $filterSql
            order by rank desc
            limit :limit
            """.trimIndent(),
            params,
        ) { rs, _ -> row(rs) }
    }

    /** Passages holding enough of the query's *discriminative* terms, ranked by IDF-weighted coverage. */
    private fun fallbackSearch(query: String, filterSql: String, base: MapSqlParameterSource, limit: Int): List<Row> {
        val s = props.search
        val lexemes = jdbc.queryForList(
            "select unnest(tsvector_to_array(to_tsvector('english', :q)))", MapSqlParameterSource("q", query), String::class.java,
        )
        val plan = KeywordFallback.plan(
            termStats.frequencies(lexemes.filterNotNull()), termStats.totalDocs(), s.fallbackCommonTermFrequency, s.fallbackMaxTerms,
        ) ?: return emptyList()

        // coverage = Σ idf(term present) / Σ idf(all terms); one bound tsquery + weight per term
        val params = MapSqlParameterSource(base.values)
            .addValue("candidates", plan.candidateQuery)
            .addValue("rankQuery", plan.rankQuery)
            .addValue("total", plan.totalWeight)
            .addValue("minCoverage", s.fallbackMinCoverage)
            .addValue("limit", limit)
        val coverage = plan.terms.withIndex().joinToString(" + ") { (i, term) ->
            params.addValue("t$i", KeywordFallback.tsQueryLiteral(term.lexeme)).addValue("w$i", term.weight)
            "(case when v.content_tsv @@ cast(:t$i as tsquery) then cast(:w$i as float8) else 0 end)"
        }
        return jdbc.query(
            """
            select * from (
                select v.id, v.book_id, b.title, b.authors, v.page, v.section, v.content,
                       ($coverage) / cast(:total as float8) as coverage,
                       ts_rank_cd(v.content_tsv, cast(:rankQuery as tsquery)) as rank
                from vector_store v join books b on b.id = v.book_id
                where v.content_tsv @@ cast(:candidates as tsquery) $filterSql
            ) c
            where coverage >= :minCoverage
            order by coverage desc, rank desc
            limit :limit
            """.trimIndent(),
            params,
        ) { rs, _ -> row(rs).copy(weight = rs.getDouble("coverage")) }
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
