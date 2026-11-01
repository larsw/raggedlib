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
        calibre_last_modified, status, chunk_count, error, indexed_at, duplicate_of
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
                duplicateOf = rs.getObject("duplicate_of") as Long?,
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

    /**
     * Updates calibre metadata; when [reset] the book goes back to PENDING. Its passages stay searchable until the
     * re-index replaces them (and lends their embeddings where nothing changed).
     */
    fun updateMetadata(r: CalibreRecord, sourceFormat: String?, reset: Boolean) {
        val resetSql = if (reset) ", status = 'PENDING', error = null" else ""
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
                   source_format = :fmt, duplicate_of = null
            where id = :id
            """.trimIndent(),
            mapOf("id" to id, "n" to chunkCount, "fmt" to sourceFormat),
        )
    }

    /**
     * Back to PENDING for re-indexing; existing passages stay (searchable) until the new ones replace them, and a
     * duplicate keeps pointing at its copy until it is decided again.
     */
    fun requeue(ids: Collection<Long>): Int = if (ids.isEmpty()) 0 else jdbc.update(
        "update books set status = 'PENDING', error = null, updated_at = now() where id in (:ids)",
        mapOf("ids" to ids),
    )

    fun requeue(id: Long) = requeue(listOf(id))

    /** The book has no passages any more (skipped, failed): status and count say so. Caller deletes the chunks. */
    fun markEmpty(id: Long, status: BookStatus, error: String?, sourceFormat: String? = null) {
        jdbc.update(
            """
            update books set status = :status, error = :error, chunk_count = 0, indexed_at = null, duplicate_of = null,
                   source_format = coalesce(:sourceFormat, source_format), updated_at = now()
            where id = :id
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("status", status.name).addValue("error", error)
                .addValue("sourceFormat", sourceFormat),
        )
    }

    /**
     * [id]'s text is in [of]: no passages of its own; searches restricted to it use [of]. Caller deletes the chunks,
     * in the same transaction: false (nothing changed) if [of] is gone by now. Its own duplicates now point at [of].
     */
    fun markDuplicate(id: Long, of: Long, note: String, sourceFormat: String?): Boolean {
        // the keeper must not be deleted (library sync) between this check and the commit
        if (jdbc.queryForList("select id from books where id = :of for key share", mapOf("of" to of), Long::class.java).isEmpty()) {
            return false
        }
        jdbc.update(
            "update books set duplicate_of = :of, updated_at = now() where duplicate_of = :id and status = 'DUPLICATE'",
            mapOf("id" to id, "of" to of),
        )
        jdbc.update(
            """
            update books set status = 'DUPLICATE', duplicate_of = :of, error = :note, chunk_count = 0, indexed_at = now(),
                   source_format = coalesce(:fmt, source_format), updated_at = now()
            where id = :id
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", id).addValue("of", of).addValue("note", note).addValue("fmt", sourceFormat),
        )
        return true
    }

    /** Keeps the book's passages but records a failure (a re-index failed; the old passages are still good). */
    fun markFailedKeepingPassages(id: Long, error: String) {
        jdbc.update(
            "update books set status = 'FAILED', error = :error, updated_at = now() where id = :id",
            mapOf("id" to id, "error" to error),
        )
    }

    /**
     * Duplicates of [keepers] go back to PENDING: the copy standing in for them is gone or lost its passages, so they
     * need passages of their own. Returns their ids.
     */
    fun requeueDuplicatesOf(keepers: Collection<Long>): List<Long> {
        if (keepers.isEmpty()) return emptyList()
        return jdbc.queryForList(
            """
            update books set status = 'PENDING', error = null, duplicate_of = null, updated_at = now()
            where status = 'DUPLICATE' and duplicate_of in (:ids) returning id
            """.trimIndent(),
            mapOf("ids" to keepers), Long::class.java,
        ).filterNotNull()
    }

    /** Duplicates whose copy was deleted (duplicate_of set null by the foreign key) go back to PENDING. */
    fun requeueOrphanedDuplicates(): List<Long> = jdbc.queryForList(
        """
        update books set status = 'PENDING', error = null, updated_at = now()
        where status = 'DUPLICATE' and duplicate_of is null returning id
        """.trimIndent(),
        emptyMap<String, Any>(), Long::class.java,
    ).filterNotNull()

    /** Books (DUPLICATE or queued again) standing in for [id]'s text. */
    fun duplicatesOf(id: Long): List<Long> = jdbc.queryForList(
        "select id from books where duplicate_of = :id", mapOf("id" to id), Long::class.java,
    ).filterNotNull()


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
