package dev.raggedlib.vectors

import dev.raggedlib.ingest.ChunkStore
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sample queries of [VectorSpaceService] against the real schema: the same rows, in the same order, as ordering
 * every passage by md5(id, seed) (what they replaced), a smaller sample the start of a larger one (what
 * [SampleStore] relies on), and embeddings in binary as they are in text.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SampleQueryTest {
    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer = PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18").asCompatibleSubstituteFor("postgres"),
        )
    }

    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }
    private var library = 0L
    private var other = 0L

    private fun vector(random: Random) = (1..1024).joinToString(",", "[", "]") { (random.nextFloat() * 2 - 1).toString() }

    @BeforeAll
    fun schemaAndRows() {
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).load().migrate()
        library = jdbc.queryForObject("insert into libraries (name, path) values ('A', '/a') returning id", Long::class.java)!!
        other = jdbc.queryForObject("insert into libraries (name, path) values ('B', '/b') returning id", Long::class.java)!!
        val random = Random(7)
        for (lib in listOf(library, other)) {
            for (b in 1..30) {
                val book = jdbc.queryForObject(
                    "insert into books (library_id, calibre_id, title, authors, status) values (?, ?, ?, '{Ann}', 'INDEXED') returning id",
                    Long::class.java, lib, b, "Book $b of $lib",
                )!!
                jdbc.update("insert into book_vectors (book_id, embedding, passages) values (?, cast(? as vector), 10)", book, vector(random))
                jdbc.batchUpdate(
                    "insert into vector_store (book_id, chunk_index, page, section, content, embedding) values (?, ?, ?, 'Intro', ?, cast(? as vector))",
                    (1..10).map { i -> arrayOf<Any>(book, i, i, "passage $i of book $book about graphs", vector(random)) },
                )
            }
        }
        // a deleted book's passages are not sampled
        jdbc.update("update books set deleted_at = now() where library_id = ? and calibre_id = 30", library)
    }

    private fun ids(sql: String, vararg args: Any): List<String> = jdbc.query(sql, { rs, _ -> rs.getString(1) }, *args)

    @Test
    fun `passages, the same sample, in the same order, as ordering every passage`() {
        for (seed in listOf("1", "42")) for (size in listOf(3, 100, 290, 1000)) {
            val old = ids(
                """
                select v.id::text from vector_store v join active_books b on b.id = v.book_id
                where v.embedding is not null and b.library_id = ? order by md5(v.id::text || ?) limit ?
                """.trimIndent(), library, seed, size,
            )
            assertEquals(old, ids(VectorSpaceService.PASSAGE_SAMPLE_SQL, seed, library, size), "seed $seed, size $size")
        }
        assertEquals(290, ids(VectorSpaceService.PASSAGE_SAMPLE_SQL, "1", library, 2000).size, "29 books of 10, one deleted")
    }

    @Test
    fun `books, the same sample, in the same order, as ordering every book vector`() {
        for (seed in listOf("1", "42")) for (size in listOf(3, 10, 100)) {
            val old = ids(
                """
                select b.id::text from book_vectors bv join active_books b on b.id = bv.book_id
                where b.library_id = ? order by md5(b.id::text || ?) limit ?
                """.trimIndent(), library, seed, size,
            )
            assertEquals(old, ids(VectorSpaceService.BOOK_SAMPLE_SQL, seed, library, size), "seed $seed, size $size")
        }
    }

    @Test
    fun `a smaller sample is the start of a larger one with the same seed`() {
        val large = ids(VectorSpaceService.PASSAGE_SAMPLE_SQL, "9", library, 200)
        for (size in listOf(1, 50, 199)) assertEquals(large.take(size), ids(VectorSpaceService.PASSAGE_SAMPLE_SQL, "9", library, size))
        assertTrue(ids(VectorSpaceService.PASSAGE_SAMPLE_SQL, "10", library, 200) != large, "another seed, another sample")
    }

    @Test
    fun `embeddings in binary are the same as in text`() {
        val rows = jdbc.query(
            "select vector_send(embedding), embedding::text from vector_store limit 20",
        ) { rs, _ -> rs.getBytes(1) to rs.getString(2) }
        assertEquals(20, rows.size)
        for ((binary, text) in rows) {
            val parsed = VectorSpaceService.parseVectorSend(binary)
            assertEquals(1024, parsed.size)
            assertContentEquals(ChunkStore.parseVector(text), parsed)
        }
    }

    @Test
    fun `word counts take the ids as text, and find them by key`() {
        val passages = ids("select id::text from vector_store limit 3")
        val words = jdbc.query(VectorSpaceService.WORD_COUNT_PASSAGES_SQL, { rs, _ -> rs.getString(1) to rs.getString(2) }, passages.toTypedArray())
        assertEquals(passages.toSet(), words.map { it.first }.toSet())
        assertTrue(words.any { it.second == "graph" })
        val plan = jdbc.queryForList(
            "explain " + VectorSpaceService.WORD_COUNT_PASSAGES_SQL, String::class.java, passages.toTypedArray(),
        ).joinToString("\n")
        assertTrue("vector_store_pkey" in plan, plan)

        val books = ids("select id::text from books limit 2")
        val titleWords = jdbc.query(VectorSpaceService.WORD_COUNT_BOOKS_SQL, { rs, _ -> rs.getString(1) }, books.toTypedArray())
        assertEquals(books.toSet(), titleWords.toSet())
    }
}
