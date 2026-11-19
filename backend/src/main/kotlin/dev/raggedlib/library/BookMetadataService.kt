package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.ApiScope
import dev.raggedlib.calibre.CalibreExtras
import dev.raggedlib.calibre.CalibreMetadataSource
import dev.raggedlib.calibre.CalibreMetadataWriter
import dev.raggedlib.calibre.CalibreLibraryWriteLock
import dev.raggedlib.calibre.CommentText
import dev.raggedlib.calibre.Formats
import dev.raggedlib.config.ApiException
import dev.raggedlib.config.badRequest
import dev.raggedlib.ingest.IngestService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class MetadataUpdateRequest(
    val fields: Map<String, JsonNode> = emptyMap(),
    val original: Map<String, JsonNode> = emptyMap(),
)

@Service
class BookMetadataService(
    private val access: Access,
    private val books: BookRepository,
    private val calibre: CalibreMetadataSource,
    private val writer: CalibreMetadataWriter,
    private val writeLock: CalibreLibraryWriteLock,
    private val activity: BookActivityRepository,
    private val ingest: IngestService,
    private val tx: TransactionTemplate,
    private val details: BookDetailsService,
) {
    fun update(bookId: Long, request: MetadataUpdateRequest): BookDetails {
        access.requireScope(ApiScope.LIBRARY_EDIT)
        val authorized = access.requireEditableBook(bookId)
        val library = access.requireEditableLibrary(authorized.book.libraryId)

        val requested = parse(request.fields)
        if (requested.isEmpty()) badRequest("change at least one metadata field")
        if (request.original.keys != request.fields.keys) {
            badRequest("provide an original value for every changed metadata field")
        }
        val original = parse(request.original, allowNullBaseline = true)

        writeLock.withLibrary(library.root) {
            access.requireEditableLibrary(authorized.book.libraryId)
            val current = books.find(bookId) ?: dev.raggedlib.config.notFound("book $bookId not found")
            val currentRecord = calibre.find(library.root, current.book.calibreId)
                ?: dev.raggedlib.config.notFound("book ${current.book.calibreId} no longer exists in Calibre")
            val oldValues = values(currentRecord) + values(calibre.extras(library.root, current.book.calibreId))
            val merge = mergeMetadataFields(original, oldValues, requested)
            if (merge.conflicts.isNotEmpty()) {
                throw ApiException(
                    HttpStatus.CONFLICT,
                    "metadata changed in Calibre; choose which values to keep and save again",
                    mapOf("conflicts" to merge.conflicts),
                )
            }
            val changed = merge.changes
            val updated = if (changed.isNotEmpty()) {
                val cliFields = changed.map { (name, value) ->
                    CALIBRE_FIELDS.getValue(name) to toCalibreValue(name, value)
                }.toMap()
                writer.setFields(library, current.book.calibreId, cliFields)
                calibre.find(library.root, current.book.calibreId)
                    ?: error("calibredb didn't return book ${current.book.calibreId} after updating it")
            } else {
                currentRecord
            }
            val titleChanged = current.book.title != updated.title
            val languagesChanged = current.book.languages != updated.languages
            val reindex = titleChanged || (languagesChanged && current.book.ocrPages > 0)
            tx.executeWithoutResult {
                books.updateMetadata(bookId, updated, current.book.sourceFormat ?: Formats.choose(updated.formats.keys), reset = reindex)
                if (changed.isNotEmpty()) {
                    val newValues = values(updated) + values(calibre.extras(library.root, current.book.calibreId))
                    val changes = changed.keys.associateWith { name ->
                        mapOf("old" to oldValues[name], "new" to newValues[name])
                    }
                    val actor = access.caller().userId?.let { access.user().label } ?: access.caller().label
                    activity.recordEdit(bookId, current.book.libraryId, current.book.calibreId, updated.title, actor, changes)
                }
            }
            if (reindex) ingest.enqueue(bookId)
        }
        return details.details(bookId)
    }

    private fun parse(fields: Map<String, JsonNode>, allowNullBaseline: Boolean = false): Map<String, Any?> {
        if (fields.isEmpty()) badRequest("provide fields to change")
        val unknown = fields.keys - CALIBRE_FIELDS.keys
        if (unknown.isNotEmpty()) badRequest("unknown metadata field(s): ${unknown.sorted().joinToString()}")
        return fields.mapValues { (name, node) ->
            when (name) {
                "title" -> text(node, name).trim().also { if (it.isEmpty()) badRequest("title must not be empty") }
                "series", "publisher" -> if (node.isNull) null else text(node, name).trim().ifEmpty { null }
                "pubdate" -> if (node.isNull || text(node, name).isBlank()) null else
                    runCatching { LocalDate.parse(text(node, name).trim()).toString() }
                        .getOrElse { badRequest("pubdate must be an ISO date (YYYY-MM-DD)") }
                "seriesIndex" -> if (node.isNull) null else {
                    if (!node.isNumber) badRequest("seriesIndex must be a number or null")
                    node.doubleValue().takeIf { it.isFinite() && it >= 0.0 }
                        ?: badRequest("seriesIndex must be a non-negative number")
                }
                "titleSort", "authorSort" -> if (node.isNull) null else text(node, name).trim()
                "description" -> if (allowNullBaseline && node.isNull) null else text(node, name)
                "rating" -> if (node.isNull) null else {
                    if (!node.isNumber) badRequest("rating must be a number or null")
                    node.doubleValue().takeIf { it.isFinite() && it in 0.0..5.0 && it * 2 % 1.0 == 0.0 }
                        ?: badRequest("rating must be between 0 and 5 in half-star increments")
                }
                "addedAt" -> {
                    if (allowNullBaseline && node.isNull) return@mapValues null
                    if (node.isNull) badRequest("addedAt must be a date and time")
                    runCatching { Instant.parse(text(node, name)) }
                        .getOrElse { badRequest("addedAt must be an ISO timestamp") }
                }
                "identifiers" -> {
                    if (!node.isObject) badRequest("identifiers must be an object of type/value pairs")
                    node.properties().associate { (type, item) ->
                        if (!type.matches(Regex("[A-Za-z0-9_.-]+"))) badRequest("invalid identifier type '$type'")
                        type to text(item, "identifiers.$type").trim()
                            .also { if (',' in it) badRequest("identifier values must not contain commas") }
                    }.filterValues { it.isNotEmpty() }
                }
                "authors", "tags", "languages" -> {
                    if (!node.isArray) badRequest("$name must be an array of strings")
                    node.values().map { item ->
                        if (!item.isTextual) badRequest("$name must contain only strings")
                        item.stringValue().trim()
                    }.filter { it.isNotEmpty() }
                }
                else -> badRequest("unknown metadata field '$name'")
            }
        }
    }

    private fun text(node: JsonNode, name: String): String {
        if (!node.isTextual) badRequest("$name must be a string")
        return node.stringValue()
    }

    private fun values(record: dev.raggedlib.calibre.CalibreRecord): Map<String, Any?> = mapOf(
        "title" to record.title,
        "authors" to record.authors,
        "tags" to record.tags,
        "series" to record.series,
        "seriesIndex" to record.seriesIndex,
        "publisher" to record.publisher,
        "pubdate" to record.pubdate?.atZone(ZoneOffset.UTC)?.toLocalDate()?.toString(),
        "languages" to record.languages,
    )

    private fun values(extras: CalibreExtras?): Map<String, Any?> {
        val values = mapOf(
            "titleSort" to extras?.titleSort,
            "authorSort" to extras?.authorSort,
            "description" to extras?.description,
            "identifiers" to extras?.identifiers.orEmpty(),
            "rating" to extras?.rating,
            "addedAt" to extras?.addedAt,
        )
        return if (extras?.publisher != null) values + ("publisher" to extras.publisher) else values
    }

    private fun toCalibreValue(name: String, value: Any?): String = when (name) {
        "authors" -> (value as List<*>).joinToString(" & ")
        "tags", "languages" -> (value as List<*>).joinToString(",")
        "seriesIndex" -> (value as? Double)?.toString().orEmpty()
        "description" -> CommentText.toSimpleHtml(value as? String ?: "")
        "identifiers" -> (value as? Map<*, *>)
            ?.entries
            ?.sortedBy { it.key.toString() }
            ?.joinToString(",") { (type, identifier) -> "$type:$identifier" }
            .orEmpty()
        "rating" -> value?.toString().orEmpty()
        else -> value?.toString().orEmpty()
    }

    private companion object {
        val CALIBRE_FIELDS = mapOf(
            "title" to "title",
            "authors" to "authors",
            "tags" to "tags",
            "series" to "series",
            "seriesIndex" to "series_index",
            "publisher" to "publisher",
            "pubdate" to "pubdate",
            "languages" to "languages",
            "titleSort" to "title_sort",
            "authorSort" to "author_sort",
            "description" to "comments",
            "identifiers" to "identifiers",
            "rating" to "rating",
            "addedAt" to "timestamp",
        )
    }
}
