package dev.raggedlib.calibre

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Reads calibre's metadata.db directly, read-only. Works while the calibre GUI is open and on read-only
 * mounts (falls back to immutable=1 when mode=ro cannot open, e.g. WAL files cannot be created).
 */
class MetadataDbReader(private val libraryRoot: Path) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun read(): List<CalibreRecord> {
        val db = libraryRoot.resolve("metadata.db")
        require(Files.isRegularFile(db)) { "metadata.db not found in $libraryRoot" }
        return open(db).use { readAll(it) }
    }

    private fun open(db: Path): Connection {
        Class.forName("org.sqlite.JDBC")
        val uri = db.toAbsolutePath().toUri().rawPath // percent-encoded absolute path
        return try {
            DriverManager.getConnection("jdbc:sqlite:file:$uri?mode=ro").also {
                it.createStatement().use { s -> s.executeQuery("select count(*) from books").close() }
            }
        } catch (e: Exception) {
            log.info("Opening metadata.db with mode=ro failed ({}), retrying with immutable=1", e.message)
            DriverManager.getConnection("jdbc:sqlite:file:$uri?mode=ro&immutable=1")
        }
    }

    private fun readAll(c: Connection): List<CalibreRecord> {
        fun multi(sql: String): Map<Long, List<String>> {
            val out = linkedMapOf<Long, MutableList<String>>()
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    while (rs.next()) out.getOrPut(rs.getLong(1)) { mutableListOf() }.add(rs.getString(2))
                }
            }
            return out
        }

        val authors = multi("select l.book, a.name from books_authors_link l join authors a on a.id = l.author order by l.book, l.id")
        val tags = multi("select l.book, t.name from books_tags_link l join tags t on t.id = l.tag order by l.book, l.id")
        val langs = multi("select l.book, g.lang_code from books_languages_link l join languages g on g.id = l.lang_code order by l.book, l.item_order")
        val series = multi("select l.book, s.name from books_series_link l join series s on s.id = l.series")
        val formats = linkedMapOf<Long, MutableMap<String, String>>()
        c.createStatement().use { s ->
            s.executeQuery("select d.book, d.format, d.name, b.path from data d join books b on b.id = d.book order by d.book, d.id").use { rs ->
                while (rs.next()) {
                    val fmt = rs.getString(2).uppercase()
                    formats.getOrPut(rs.getLong(1)) { linkedMapOf() }[fmt] =
                        "${rs.getString(4)}/${rs.getString(3)}.${fmt.lowercase()}"
                }
            }
        }
        val out = mutableListOf<CalibreRecord>()
        c.createStatement().use { s ->
            s.executeQuery("select id, title, pubdate, series_index, path, has_cover, last_modified from books order by id").use { rs ->
                while (rs.next()) {
                    val id = rs.getLong(1)
                    val ser = series[id]?.firstOrNull()
                    out += CalibreRecord(
                        id = id,
                        title = rs.getString(2) ?: "Unknown",
                        authors = authors[id] ?: emptyList(),
                        tags = tags[id] ?: emptyList(),
                        series = ser,
                        seriesIndex = if (ser != null) rs.getDouble(4) else null,
                        languages = langs[id] ?: emptyList(),
                        pubdate = CalibreDates.pubdate(rs.getString(3)),
                        formats = formats[id] ?: emptyMap(),
                        coverPath = if (rs.getBoolean(6)) "${rs.getString(5)}/cover.jpg" else null,
                        lastModified = CalibreDates.lastModified(rs.getString(7)),
                    )
                }
            }
        }
        return out
    }
}
