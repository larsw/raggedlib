package dev.raggedlib.library

import dev.raggedlib.calibre.CalibreRecord
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.sql.ResultSet
import java.sql.Timestamp

@Repository
class BookRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {
    private val columns = """
        id, title, authors, tags, series, series_index, languages, pubdate, formats, source_format, cover_path,
        calibre_last_modified, status, chunk_count, error, indexed_at
    """.trimIndent()

    private val rowMapper = RowMapper { rs, _ -> mapRow(rs) }

    private fun mapRow(rs: ResultSet): BookRow {
        val formatPaths: Map<String, String> = mapper.readValue(rs.getString("formats"))
        val cover = rs.getString("cover_path")
        return BookRow(
            book = Book(
                id = rs.getLong("id"),
                title = rs.getString("title"),
                authors = rs.strings("authors"),
                tags = rs.strings("tags"),
                series = rs.getString("series"),
                seriesIndex = rs.getObject("series_index") as Double?,
                languages = rs.strings("languages"),
                pubdate = rs.getTimestamp("pubdate")?.toInstant(),
                formats = formatPaths.keys.sorted(),
                sourceFormat = rs.getString("source_format"),
                hasCover = cover != null,
                status = BookStatus.valueOf(rs.getString("status")),
                chunkCount = rs.getInt("chunk_count"),
                error = rs.getString("error"),
                indexedAt = rs.getTimestamp("indexed_at")?.toInstant(),
            ),
            formatPaths = formatPaths,
            coverPath = cover,
            lastModified = rs.getTimestamp("calibre_last_modified")?.toInstant(),
        )
    }

    fun find(id: Long): BookRow? =
        jdbc.query("select $columns from books where id = :id", mapOf("id" to id), rowMapper).firstOrNull()

    fun findAll(): List<BookRow> = jdbc.query("select $columns from books", rowMapper)

    fun search(q: String?, status: BookStatus?, tag: String?, page: Int, size: Int, sort: String): Page<Book> {
        val where = mutableListOf<String>()
        val params = MapSqlParameterSource()
        if (!q.isNullOrBlank()) {
            where += "(title ilike :q or array_to_string(authors, ' ') ilike :q)"
            params.addValue("q", "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
        }
        if (status != null) {
            where += "status = :status"
            params.addValue("status", status.name)
        }
        if (!tag.isNullOrBlank()) {
            where += ":tag = any(tags)"
            params.addValue("tag", tag)
        }
        val whereSql = if (where.isEmpty()) "" else "where " + where.joinToString(" and ")
        val desc = sort.startsWith("-")
        val orderSql = when (sort.removePrefix("-")) {
            "author" -> "lower(coalesce(authors[1], '')) ${dir(desc)}, lower(title)"
            "pubdate" -> "pubdate ${dir(!desc)} nulls last, lower(title)" // newest first by default
            "indexedAt" -> "indexed_at ${dir(!desc)} nulls last, lower(title)" // most recent first by default
            "id" -> "id ${dir(desc)}"
            else -> "lower(title) ${dir(desc)}, id"
        }
        val total = jdbc.queryForObject("select count(*) from books $whereSql", params, Long::class.java) ?: 0L
        params.addValue("limit", size).addValue("offset", page.toLong() * size)
        val items = jdbc.query("select $columns from books $whereSql order by $orderSql limit :limit offset :offset", params, rowMapper)
            .map { it.book }
        return Page(items, total, page, size)
    }

    private fun dir(desc: Boolean) = if (desc) "desc" else "asc"

    fun tags(): List<TagCount> = jdbc.query(
        "select t, count(*) c from books, unnest(tags) t group by t order by c desc, t",
        emptyMap<String, Any>(),
    ) { rs, _ -> TagCount(rs.getString(1), rs.getInt(2)) }

    fun insert(r: CalibreRecord, sourceFormat: String?) {
        jdbc.update(
            """
            insert into books (id, title, authors, tags, series, series_index, languages, pubdate, formats, source_format,
                               cover_path, calibre_last_modified, status)
            values (:id, :title, :authors, :tags, :series, :seriesIndex, :languages, :pubdate, cast(:formats as jsonb),
                    :sourceFormat, :cover, :lastModified, 'PENDING')
            """.trimIndent(),
            recordParams(r, sourceFormat),
        )
    }

    /** Updates calibre metadata; when [reset] the book goes back to PENDING (caller deletes chunks). */
    fun updateMetadata(r: CalibreRecord, sourceFormat: String?, reset: Boolean) {
        val resetSql = if (reset) ", status = 'PENDING', chunk_count = 0, error = null, indexed_at = null" else ""
        jdbc.update(
            """
            update books set title = :title, authors = :authors, tags = :tags, series = :series, series_index = :seriesIndex,
                   languages = :languages, pubdate = :pubdate, formats = cast(:formats as jsonb), source_format = :sourceFormat,
                   cover_path = :cover, calibre_last_modified = :lastModified, updated_at = now() $resetSql
            where id = :id
            """.trimIndent(),
            recordParams(r, sourceFormat),
        )
    }

    private fun recordParams(r: CalibreRecord, sourceFormat: String?) = MapSqlParameterSource()
        .addValue("id", r.id)
        .addValue("title", r.title)
        .addValue("authors", r.authors.toTypedArray())
        .addValue("tags", r.tags.toTypedArray())
        .addValue("series", r.series)
        .addValue("seriesIndex", r.seriesIndex)
        .addValue("languages", r.languages.toTypedArray())
        .addValue("pubdate", r.pubdate?.let(Timestamp::from))
        .addValue("formats", mapper.writeValueAsString(r.formats))
        .addValue("sourceFormat", sourceFormat)
        .addValue("cover", r.coverPath)
        .addValue("lastModified", r.lastModified?.let(Timestamp::from))

    fun delete(ids: Collection<Long>): Int =
        if (ids.isEmpty()) 0 else jdbc.update("delete from books where id in (:ids)", mapOf("ids" to ids))

    fun setStatus(id: Long, status: BookStatus, error: String? = null, sourceFormat: String? = null) {
        jdbc.update(
            """
            update books set status = :status, error = :error, updated_at = now(),
                   source_format = coalesce(:sourceFormat, source_format)
            where id = :id
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("status", status.name).addValue("error", error)
                .addValue("sourceFormat", sourceFormat),
        )
    }

    fun markIndexed(id: Long, chunkCount: Int, sourceFormat: String) {
        jdbc.update(
            """
            update books set status = 'INDEXED', chunk_count = :n, error = null, indexed_at = now(), updated_at = now(),
                   source_format = :fmt
            where id = :id
            """.trimIndent(),
            mapOf("id" to id, "n" to chunkCount, "fmt" to sourceFormat),
        )
    }

    fun resetToPending(id: Long) {
        jdbc.update(
            "update books set status = 'PENDING', chunk_count = 0, error = null, indexed_at = null, updated_at = now() where id = :id",
            mapOf("id" to id),
        )
    }

    fun idsWithStatus(statuses: Collection<BookStatus>, restrictTo: Collection<Long>?): List<Long> {
        val params = MapSqlParameterSource("statuses", statuses.map { it.name })
        var sql = "select id from books where status in (:statuses)"
        if (restrictTo != null) {
            if (restrictTo.isEmpty()) return emptyList()
            sql += " and id in (:ids)"
            params.addValue("ids", restrictTo)
        }
        return jdbc.queryForList("$sql order by id", params, Long::class.java).filterNotNull()
    }

    fun statusOf(id: Long): BookStatus? = jdbc.queryForList(
        "select status from books where id = :id", mapOf("id" to id), String::class.java,
    ).firstOrNull()?.let(BookStatus::valueOf)

    fun statusCounts(): Map<BookStatus, Int> {
        val counts = BookStatus.entries.associateWith { 0 }.toMutableMap()
        jdbc.query("select status, count(*) from books group by status", emptyMap<String, Any>()) { rs ->
            counts[BookStatus.valueOf(rs.getString(1))] = rs.getInt(2)
        }
        return counts
    }

    fun totalChunks(): Long =
        jdbc.queryForObject("select coalesce(sum(chunk_count), 0) from books", emptyMap<String, Any>(), Long::class.java) ?: 0L

    fun avgChunksPerIndexedBook(): Double? = jdbc.queryForObject(
        "select avg(chunk_count)::float8 from books where status = 'INDEXED' and chunk_count > 0",
        emptyMap<String, Any>(), Double::class.java,
    )

    /** Books left mid-pipeline by a crash/restart. */
    fun stuckIds(): List<Long> = idsWithStatus(listOf(BookStatus.EXTRACTING, BookStatus.EMBEDDING), null)
}

private fun ResultSet.strings(col: String): List<String> {
    val arr = getArray(col) ?: return emptyList()
    @Suppress("UNCHECKED_CAST")
    return (arr.array as Array<String>).toList()
}
