package dev.raggedlib.search

data class SearchRequest(
    val query: String = "",
    val topK: Int? = null,
    val bookIds: List<Long>? = null,
    val tags: List<String>? = null,
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
    val score: Double,
)
