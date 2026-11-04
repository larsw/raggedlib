package dev.raggedlib.vectors

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * A book's vector: the mean of its passages' embeddings, normalized to unit length (they are too, so it compares
 * by cosine like a passage does). Computed in the database from `vector_store` (pgvector's avg and l2_normalize),
 * so it never needs the book read or embedded again: about 3 s for a whole library of ~1,800 books.
 */
@Repository
class BookVectors(private val jdbc: NamedParameterJdbcTemplate) {

    /** Recomputes [bookId]'s vector from its current passages, or drops it if it has none. Run after they change. */
    fun refresh(bookId: Long) {
        jdbc.update("delete from book_vectors where book_id = :id", mapOf("id" to bookId))
        jdbc.update(
            """
            insert into book_vectors (book_id, embedding, passages)
            select book_id, l2_normalize(avg(embedding)), count(*) from vector_store
            where book_id = :id and embedding is not null group by book_id
            """.trimIndent(),
            mapOf("id" to bookId),
        )
    }

    /**
     * Computes the vectors of books (of [libraryId], or every library) that have passages but no vector, or one
     * older than their last indexing. Returns how many were computed.
     */
    fun computeMissing(libraryId: Long?): Int = jdbc.update(
        """
        insert into book_vectors (book_id, embedding, passages)
        select v.book_id, l2_normalize(avg(v.embedding)), count(*)
        from vector_store v
        where v.embedding is not null and v.book_id in (${missingBooksSql(libraryId)})
        group by v.book_id
        on conflict (book_id) do update
            set embedding = excluded.embedding, passages = excluded.passages, computed_at = now()
        """.trimIndent(),
        params(libraryId),
    )

    /** Books of [libraryId] with passages but no (current) vector: what [computeMissing] would compute. */
    fun countMissing(libraryId: Long?): Int =
        jdbc.queryForObject("select count(*) from (${missingBooksSql(libraryId)}) m", params(libraryId), Int::class.java) ?: 0

    private fun params(libraryId: Long?) = MapSqlParameterSource("libraryId", libraryId)

    private fun missingBooksSql(libraryId: Long?): String {
        val inLibrary = if (libraryId != null) "and b.library_id = :libraryId" else ""
        return """
            select b.id from books b
            where b.chunk_count > 0 $inLibrary
              and not exists (
                  select 1 from book_vectors bv
                  where bv.book_id = b.id and bv.computed_at >= coalesce(b.indexed_at, '-infinity'::timestamptz)
              )
        """.trimIndent()
    }
}
