package dev.raggedlib.library

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant

data class BookEdit(val actor: String, val at: Instant)

data class BookActivityChange(val old: JsonNode?, val new: JsonNode?)

data class BookActivityEntry(
    val id: Long,
    val libraryId: Long,
    val libraryName: String,
    val bookId: Long?,
    val bookTitle: String?,
    val actor: String,
    val action: String,
    val changes: Map<String, BookActivityChange>,
    val at: Instant,
)

data class BookActivityPage(
    val items: List<BookActivityEntry>,
    val total: Long,
    val page: Int,
    val size: Int,
    val actors: List<String>,
)

@Repository
class BookActivityRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun recordEdit(
        bookId: Long,
        libraryId: Long,
        calibreId: Long,
        title: String,
        actor: String,
        changes: Map<String, Map<String, Any?>>,
    ) {
        jdbc.update(
            """
            insert into book_activity (book_id, library_id, calibre_id, book_title, actor, action, changes)
            values (:bookId, :libraryId, :calibreId, :title, :actor, 'EDIT_METADATA', cast(:changes as jsonb))
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("bookId", bookId)
                .addValue("libraryId", libraryId)
                .addValue("calibreId", calibreId)
                .addValue("title", title)
                .addValue("actor", actor)
                .addValue("changes", mapper.writeValueAsString(changes)),
        )
    }

    fun recordLibraryAction(libraryId: Long, actor: String, action: String) {
        jdbc.update(
            """
            insert into book_activity (library_id, actor, action)
            values (:libraryId, :actor, :action)
            """.trimIndent(),
            mapOf("libraryId" to libraryId, "actor" to actor, "action" to action),
        )
    }

    fun list(
        libraryIds: Set<Long>,
        user: String?,
        action: String?,
        page: Int,
        size: Int,
    ): BookActivityPage {
        require(libraryIds.isNotEmpty())
        val where = buildList {
            add("a.library_id in (:libraryIds)")
            if (user != null) add("a.actor = :actor")
            if (action != null) add("a.action = :action")
        }.joinToString(" and ")
        val params = MapSqlParameterSource("libraryIds", libraryIds)
            .addValue("actor", user)
            .addValue("action", action)
        val total = jdbc.queryForObject(
            "select count(*) from book_activity a where $where",
            params,
            Long::class.java,
        ) ?: 0L
        val items = jdbc.query(
            """
            select a.id, a.library_id, l.name as library_name,
                case when b.id is not null then a.book_id else null end as book_id,
                a.book_title, a.actor, a.action, a.changes::text as changes, a.created_at
            from book_activity a
            join libraries l on l.id = a.library_id
            left join books b on b.id = a.book_id
            where $where
            order by a.created_at desc, a.id desc
            limit :limit offset :offset
            """.trimIndent(),
            params.addValue("limit", size).addValue("offset", page.toLong() * size),
        ) { rs, _ ->
            val bookId = rs.getLong("book_id").let { if (rs.wasNull()) null else it }
            val changes = mapper.readTree(rs.getString("changes")).properties().associate { (field, node) ->
                field to BookActivityChange(node.get("old"), node.get("new"))
            }
            BookActivityEntry(
                rs.getLong("id"),
                rs.getLong("library_id"),
                rs.getString("library_name"),
                bookId,
                rs.getString("book_title"),
                rs.getString("actor"),
                rs.getString("action"),
                changes,
                rs.getTimestamp("created_at").toInstant(),
            )
        }
        val actors = jdbc.query(
            "select distinct actor from book_activity where library_id in (:libraryIds) order by actor",
            mapOf("libraryIds" to libraryIds),
        ) { rs, _ -> rs.getString("actor") }
        return BookActivityPage(items, total, page, size, actors)
    }

    fun lastEdit(bookId: Long): BookEdit? = jdbc.query(
        """
        select actor, created_at from book_activity
        where book_id = :bookId and action = 'EDIT_METADATA'
        order by created_at desc, id desc limit 1
        """.trimIndent(),
        mapOf("bookId" to bookId),
    ) { rs, _ -> BookEdit(rs.getString("actor"), rs.getTimestamp("created_at").toInstant()) }.firstOrNull()
}
