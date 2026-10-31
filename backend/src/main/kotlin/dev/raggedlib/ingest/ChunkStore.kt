package dev.raggedlib.ingest

import com.pgvector.PGvector
import dev.raggedlib.extract.Chunk
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Writes/deletes rows of the PgVectorStore-compatible `vector_store` table. */
@Repository
class ChunkStore(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun deleteForBook(bookId: Long): Int = jdbc.update("delete from vector_store where book_id = ?", bookId)

    fun insert(bookId: Long, title: String, authors: List<String>, chunks: List<Chunk>, embeddings: List<FloatArray>) {
        require(chunks.size == embeddings.size)
        val sql = """
            insert into vector_store (id, content, metadata, embedding, book_id, chunk_index, page, section)
            values (?, ?, cast(? as jsonb), ?, ?, ?, ?, ?)
        """.trimIndent()
        chunks.indices.chunked(500).forEach { idxs ->
            jdbc.batchUpdate(sql, idxs.map { i ->
                val c = chunks[i]
                val meta = linkedMapOf<String, Any?>(
                    "bookId" to bookId, "title" to title, "authors" to authors,
                    "chunkIndex" to c.index, "page" to c.page, "section" to c.section,
                )
                arrayOf<Any?>(
                    UUID.randomUUID(), c.text, mapper.writeValueAsString(meta), PGvector(embeddings[i]),
                    bookId, c.index, c.page, c.section,
                )
            })
        }
    }
}
