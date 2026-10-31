package dev.raggedlib.search

import dev.raggedlib.config.RaggedlibProperties
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Document frequencies of full-text lexemes, for IDF weighting.
 *
 * Common lexemes come for free from the planner statistics that ANALYZE keeps for the `content_tsv`
 * column (`pg_stats.most_common_elems`, the ~1000 most frequent lexemes with their frequencies). Anything
 * not in that list is rarer than all of them, and an exact count is cheap for rare lexemes (a GIN lookup
 * over a few thousand rows), so those are counted and cached. Both caches expire after
 * `raggedlib.search.term-stats-ttl-seconds` because the corpus grows while indexing.
 */
@Component
class TermStatistics(private val jdbc: NamedParameterJdbcTemplate, props: RaggedlibProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ttlNanos = props.search.termStatsTtlSeconds.coerceAtLeast(1) * 1_000_000_000

    private class Snapshot(val totalDocs: Double, val mostCommon: Map<String, Double>, val at: Long)
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
            snap.mostCommon[lexeme]
                ?: counts[lexeme]?.takeIf { now - it.at < ttlNanos }?.frequency
                // not under a map lock: a concurrent duplicate count is harmless, a blocked bin is not
                ?: (exactCount(lexeme) / snap.totalDocs).also { counts[lexeme] = Count(it, now) }
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
        // reltuples is -1 before the first ANALYZE/VACUUM of the table
        val estimate = jdbc.queryForObject(
            "select reltuples from pg_class where oid = 'vector_store'::regclass", emptyMap<String, Any>(), Double::class.java,
        ) ?: -1.0
        val total = if (estimate > 0) estimate
        else jdbc.queryForObject("select count(*) from vector_store", emptyMap<String, Any>(), Long::class.java)!!.toDouble()
        // most_common_elem_freqs carries extra trailing entries (for tsvector: min and max frequency), which
        // unnest() pairs with NULL elements — hence the filter.
        val common = HashMap<String, Double>()
        jdbc.query(
            """
            select t.elem, t.freq
            from pg_stats s, unnest(s.most_common_elems::text::text[], s.most_common_elem_freqs) as t(elem, freq)
            where s.schemaname = current_schema() and s.tablename = 'vector_store' and s.attname = 'content_tsv'
              and t.elem is not null
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs -> common[rs.getString(1)] = rs.getDouble(2) }
        log.debug("term statistics refreshed: {} chunks, {} common lexemes", total.toLong(), common.size)
        return Snapshot(total.coerceAtLeast(1.0), common, now)
    }

    private fun exactCount(lexeme: String): Double = jdbc.queryForObject(
        "select count(*) from vector_store where content_tsv @@ cast(:q as tsquery)",
        MapSqlParameterSource("q", KeywordFallback.tsQueryLiteral(lexeme)),
        Long::class.java,
    )!!.toDouble()

    private companion object {
        const val MAX_CACHED_COUNTS = 50_000
    }
}
