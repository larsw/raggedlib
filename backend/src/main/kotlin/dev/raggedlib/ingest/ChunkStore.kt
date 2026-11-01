package dev.raggedlib.ingest

import com.pgvector.PGvector
import dev.raggedlib.extract.Chunk
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Writes/deletes rows of the PgVectorStore-compatible `vector_store` table. */
@Repository
class ChunkStore(
    private val jdbc: JdbcTemplate,
    private val named: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
    @Value("\${spring.ai.ollama.embedding.model}") private val embeddingModel: String,
    @Value("\${raggedlib.embedding.legacy-model:qwen3-embedding:0.6b}") private val legacyModel: String,
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
                    "embeddingModel" to embeddingModel,
                )
                arrayOf<Any?>(
                    UUID.randomUUID(), c.text, mapper.writeValueAsString(meta), PGvector(embeddings[i]),
                    bookId, c.index, c.page, c.section,
                )
            })
        }
    }

    /**
     * Books other than [bookId] holding passages with these md5 [hashes] (of the passage text, as Postgres'
     * `md5(content)`), with how many of them each holds. Any status: a book queued for re-indexing keeps its
     * passages until then (so of two copies re-indexed in one run, the first sees the second's).
     */
    fun booksContaining(hashes: Collection<String>, bookId: Long): List<Duplicates.Match> {
        if (hashes.isEmpty()) return emptyList()
        return named.query(
            """
            select v.book_id, count(distinct md5(v.content)) as shared, b.chunk_count
            from vector_store v join books b on b.id = v.book_id
            where md5(v.content) = any(:hashes) and v.book_id <> :id
            group by v.book_id, b.chunk_count
            """.trimIndent(),
            MapSqlParameterSource("hashes", hashes.toTypedArray()).addValue("id", bookId),
        ) { rs, _ -> Duplicates.Match(rs.getLong(1), rs.getInt(2), rs.getInt(3)) }
    }

    /**
     * Stored embeddings of [bookId]'s passages, by the text they were computed from ([embeddingInput] of the
     * stored section and content under [title]); only rows stored under that title, by the current embedding model
     * (rows from before the model was recorded were made by `raggedlib.embedding.legacy-model`).
     */
    /** Number of different passage texts [bookId] has. */
    fun distinctPassages(bookId: Long): Int = named.queryForObject(
        "select count(distinct md5(content)) from vector_store where book_id = :id", mapOf("id" to bookId), Int::class.java,
    ) ?: 0

    fun embeddingsByInput(bookId: Long, title: String, embeddingInput: (Chunk) -> String): Map<String, FloatArray> {
        val out = HashMap<String, FloatArray>()
        named.query(
            """
            select section, content, embedding::text from vector_store
            where book_id = :id and metadata->>'title' = :title
              and coalesce(metadata->>'embeddingModel', :legacy) = :model
            """.trimIndent(),
            MapSqlParameterSource("id", bookId).addValue("title", title).addValue("model", embeddingModel)
                .addValue("legacy", legacyModel),
        ) { rs ->
            val input = embeddingInput(Chunk(0, rs.getString(2), null, rs.getString(1)))
            out[input] = parseVector(rs.getString(3))
        }
        return out
    }

    internal companion object {
        /** pgvector's text form, "[0.1,-2,3e-05]". */
        fun parseVector(text: String): FloatArray {
            val body = text.trim().removePrefix("[").removeSuffix("]")
            if (body.isBlank()) return FloatArray(0)
            val parts = body.split(',')
            return FloatArray(parts.size) { parts[it].trim().toFloat() }
        }
    }
}
