package dev.raggedlib.search

import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.library.BooksVisibilityChanged
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Document frequencies of full-text lexemes, for IDF weighting.
 *
 * Counts are restricted to active books, so soft-deleted passages don't influence search ranking. Counts and
 * the total corpus size are cached for `raggedlib.search.term-stats-ttl-seconds` and invalidated when visibility
 * changes.
 */
@Component
class TermStatistics(private val jdbc: NamedParameterJdbcTemplate, props: RaggedlibProperties) {
    private val ttlNanos = props.search.termStatsTtlSeconds.coerceAtLeast(1) * 1_000_000_000

    private class Snapshot(val totalDocs: Double, val at: Long)
    private class Count(val frequency: Double, val at: Long)

    @Volatile private var snapshot: Snapshot? = null
    private val counts = ConcurrentHashMap<String, Count>()

    /** Number of chunks (planner estimate; exact count before the first ANALYZE). */
    fun totalDocs(): Double = snapshot().totalDocs

    /** lexeme → fraction of chunks whose content_tsv contains it. */
    fun frequencies(lexemes: Collection<String>): Map<String, Double> {
        val snap = snapshot()
        val now = System.nanoTime()
        if (counts.size > MAX_CACHED_COUNTS) counts.clear()
        return lexemes.associateWith { lexeme ->
            counts[lexeme]?.takeIf { now - it.at < ttlNanos }?.frequency
                // not under a map lock: a concurrent duplicate count is harmless, a blocked bin is not
                ?: (exactCount(lexeme) / snap.totalDocs).also { counts[lexeme] = Count(it, now) }
        }
    }

    @EventListener
    fun onBooksVisibilityChanged(event: BooksVisibilityChanged) {
        if (event.count > 0) {
            snapshot = null
            counts.clear()
        }
    }

    private fun snapshot(): Snapshot {
        val now = System.nanoTime()
        snapshot?.let { if (now - it.at < ttlNanos) return it }
        synchronized(this) {
            snapshot?.let { if (now - it.at < ttlNanos) return it }
            return load(now).also { snapshot = it; counts.clear() }
        }
    }

    private fun load(now: Long): Snapshot {
        val total = jdbc.queryForObject(
            "select count(*) from vector_store v join active_books b on b.id = v.book_id",
            emptyMap<String, Any>(),
            Long::class.java,
        )?.toDouble() ?: 0.0
        return Snapshot(total.coerceAtLeast(1.0), now)
    }

    private fun exactCount(lexeme: String): Double = jdbc.queryForObject(
        """
        select count(*) from vector_store v join active_books b on b.id = v.book_id
        where v.content_tsv @@ cast(:q as tsquery)
        """.trimIndent(),
        MapSqlParameterSource("q", KeywordFallback.tsQueryLiteral(lexeme)),
        Long::class.java,
    )!!.toDouble()

    private companion object {
        const val MAX_CACHED_COUNTS = 50_000
    }
}
