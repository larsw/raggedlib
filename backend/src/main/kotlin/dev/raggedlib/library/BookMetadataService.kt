package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.ApiScope
import dev.raggedlib.auth.LibraryRole
import dev.raggedlib.calibre.CalibreMetadataSource
import dev.raggedlib.calibre.CalibreMetadataWriter
import dev.raggedlib.calibre.CalibreLibraryWriteLock
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

data class MetadataUpdateRequest(val fields: Map<String, JsonNode> = emptyMap())

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
        val authorized = access.requireBook(bookId, LibraryRole.CURATOR)
        val library = accessLibrary(authorized.book.libraryId)
        if (!library.writable) {
            val reason = library.readOnlyReason?.replace(library.root.toString(), library.name)
            throw ApiException(HttpStatus.FORBIDDEN, reason ?: "library '${library.name}' is read-only")
        }

        val requested = parse(request.fields)
        if (requested.isEmpty()) badRequest("change at least one metadata field")

        writeLock.withLibrary(library.root) {
            val current = books.find(bookId) ?: dev.raggedlib.config.notFound("book $bookId not found")
            val oldValues = values(current.book)
            val changed = requested.filter { (name, value) -> oldValues[name] != value }
            if (changed.isNotEmpty()) {
                val cliFields = changed.map { (name, value) ->
                    CALIBRE_FIELDS.getValue(name) to toCalibreValue(name, value)
                }.toMap()
                writer.setFields(library, current.book.calibreId, cliFields)
                val updated = calibre.find(library.root, current.book.calibreId)
                    ?: error("calibredb didn't return book ${current.book.calibreId} after updating it")
                val newValues = values(updated)
                val changes = changed.keys.associateWith { name ->
                    mapOf("old" to oldValues[name], "new" to newValues[name])
                }
                val titleChanged = current.book.title != updated.title
                val languagesChanged = current.book.languages != updated.languages
                val reindex = titleChanged || (languagesChanged && current.book.ocrPages > 0)
                val actor = access.caller().userId?.let { access.user().label } ?: access.caller().label

                tx.executeWithoutResult {
                    books.updateMetadata(bookId, updated, current.book.sourceFormat ?: Formats.choose(updated.formats.keys), reset = reindex)
                    activity.recordEdit(bookId, current.book.libraryId, current.book.calibreId, updated.title, actor, changes)
                }
                if (reindex) ingest.enqueue(bookId)
            }
        }
        return details.details(bookId)
    }

    private fun accessLibrary(id: Long) = access.requireLibrary(id, LibraryRole.CURATOR)

    private fun parse(fields: Map<String, JsonNode>): Map<String, Any?> {
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

    private fun values(book: Book): Map<String, Any?> = mapOf(
        "title" to book.title,
        "authors" to book.authors,
        "tags" to book.tags,
        "series" to book.series,
        "seriesIndex" to book.seriesIndex,
        "publisher" to book.publisher,
        "pubdate" to book.pubdate?.atZone(ZoneOffset.UTC)?.toLocalDate()?.toString(),
        "languages" to book.languages,
    )

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

    private fun toCalibreValue(name: String, value: Any?): String = when (name) {
        "authors" -> (value as List<*>).joinToString(" & ")
        "tags", "languages" -> (value as List<*>).joinToString(",")
        "seriesIndex" -> (value as? Double)?.toString().orEmpty()
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
        )
    }
}
