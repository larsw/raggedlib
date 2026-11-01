package dev.raggedlib.search

data class SearchRequest(
    val query: String = "",
    val topK: Int? = null,
    val bookIds: List<Long>? = null,
    val tags: List<String>? = null,
    /** Cross-encoder reranking (default on when the server has a reranker); false = fused order only. */
    val rerank: Boolean? = null,
)

data class SourceChunk(
    val ref: Int,
    val chunkId: String,
    val bookId: Long,
    val title: String,
    val authors: List<String>,
    val page: Int?,
    val section: String?,
    val text: String,
    /** Higher = better; its scale depends on [scoreKind]. */
    val score: Double,
    val scoreKind: ScoreKind = ScoreKind.FUSED,
)

enum class ScoreKind {
    /** Reranker relevance, 0..1 — comparable across queries. */
    RERANK,
    /** Weighted-RRF score — only meaningful relative to the other results of the same query. */
    FUSED,
}
