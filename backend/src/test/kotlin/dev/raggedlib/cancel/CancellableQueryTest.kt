package dev.raggedlib.cancel

import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [cancellableQuery] against a real Postgres: a cancel must stop the query there, not just give up waiting for it. */
@Testcontainers(disabledWithoutDocker = true)
class CancellableQueryTest {
    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer = PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18").asCompatibleSubstituteFor("postgres"),
        )
    }

    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    private fun sleepingQueries(): Int = jdbc.queryForObject(
        "select count(*) from pg_stat_activity where state = 'active' and query like 'select pg_sleep%'",
        Int::class.java,
    )!!

    @Test
    fun `a cancelled query is stopped in Postgres`() {
        val cancellation = Cancellation()
        val query = CompletableFuture.supplyAsync {
            cancellation.bind { jdbc.cancellableQuery("select pg_sleep(60)", { rs, _ -> rs.getString(1) }) }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (sleepingQueries() == 0) {
            check(System.nanoTime() < deadline) { "the query never started" }
            Thread.sleep(20)
        }

        val cancelledAt = System.nanoTime()
        cancellation.cancel()
        val e = assertFailsWith<ExecutionException> { query.get(10, TimeUnit.SECONDS) }
        assertIs<RequestCancelledException>(e.cause)
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAt)
        assertTrue(ms < 5_000, "took $ms ms to stop")
        assertEquals(0, sleepingQueries(), "still running in Postgres")
    }

    @Test
    fun `a query already cancelled isn't run`() {
        val cancellation = Cancellation().apply { cancel() }
        assertFailsWith<RequestCancelledException> {
            cancellation.bind { jdbc.cancellableQuery("select pg_sleep(60)", { rs, _ -> rs.getString(1) }) }
        }
        assertEquals(0, sleepingQueries())
    }

    @Test
    fun `arguments are bound, an array as one, and outside a request it is an ordinary query`() {
        val rows = jdbc.cancellableQuery(
            "select x from unnest(?::text[]) x where x <> ? order by x",
            { rs, _ -> rs.getString(1) },
            arrayOf("b", "c", "a"), "c",
        )
        assertEquals(listOf("a", "b"), rows)
        val seen = mutableListOf<Int>()
        jdbc.cancellableQueryEach("select generate_series(1, ?)", { rs -> seen += rs.getInt(1) }, 3)
        assertEquals(listOf(1, 2, 3), seen)
    }
}
