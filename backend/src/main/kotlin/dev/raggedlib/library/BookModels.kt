package dev.raggedlib.library

import java.time.Instant

enum class BookStatus { PENDING, EXTRACTING, EMBEDDING, INDEXED, FAILED, SKIPPED }

/** API representation (see docs/API.md). */
data class Book(
    val id: Long,
    val title: String,
    val authors: List<String>,
    val tags: List<String>,
    val series: String?,
    val seriesIndex: Double?,
    val languages: List<String>,
    val pubdate: Instant?,
    val formats: List<String>,
    val sourceFormat: String?,
    val hasCover: Boolean,
    val status: BookStatus,
    val chunkCount: Int,
    val error: String?,
    val indexedAt: Instant?,
)

/** Internal row with the relative file paths. */
data class BookRow(
    val book: Book,
    val formatPaths: Map<String, String>,
    val coverPath: String?,
    val lastModified: Instant?,
)

data class Page<T>(val items: List<T>, val total: Long, val page: Int, val size: Int)

data class TagCount(val name: String, val count: Int)

data class SyncResult(val added: Int, val updated: Int, val removed: Int, val total: Int, val source: String)
