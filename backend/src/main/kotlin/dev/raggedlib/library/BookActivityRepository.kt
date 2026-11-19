package dev.raggedlib.library

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant

data class BookEdit(val actor: String, val at: Instant)

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

    fun lastEdit(bookId: Long): BookEdit? = jdbc.query(
        """
        select actor, created_at from book_activity
        where book_id = :bookId and action = 'EDIT_METADATA'
        order by created_at desc, id desc limit 1
        """.trimIndent(),
        mapOf("bookId" to bookId),
    ) { rs, _ -> BookEdit(rs.getString("actor"), rs.getTimestamp("created_at").toInstant()) }.firstOrNull()
}
