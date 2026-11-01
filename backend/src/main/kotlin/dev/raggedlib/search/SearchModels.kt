package dev.raggedlib.search

data class SearchRequest(
    val query: String = "",
    val topK: Int? = null,
    val bookIds: List<Long>? = null,
    val tags: List<String>? = null,
    /** Cross-encoder reranking (default on when the server has a reranker); false = fused order only. */
    val rerank: Boolean? = null,
    /** Correct misspelled words before searching (default true); false searches for the query exactly as written. */
    val correct: Boolean? = null,
)

data class SearchResponse(
    val results: List<SourceChunk>,
    /** The query actually searched for, when spelling correction changed it; null otherwise. */
    val correctedQuery: String? = null,
    /** The words that were corrected (as typed → as searched). */
    val corrections: List<SpellingCorrection> = emptyList(),
)

/** A corrected word: [from] as typed, [to] as searched; [known]: the typed word occurs in the library (rarely). */
data class SpellingCorrection(val from: String, val to: String, val known: Boolean)

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
