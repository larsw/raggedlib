package dev.raggedlib.search

import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.ingest.IngestionFinished
import dev.raggedlib.library.BooksRemoved
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The words of the indexed passages with their document frequencies (`search_vocabulary`), which
 * [SpellingCorrector] draws corrections from. Unstemmed ('simple' configuration), so a correction is a word
 * people actually wrote, fit to show and to embed.
 *
 * Built with `ts_stat` over every passage, a full scan of `vector_store` that takes a few minutes, so it runs in
 * the background: at startup when the vocabulary is missing, stale (older than the last indexed book, or built
 * with other settings) or the library is empty; after an indexing run that stored passages; and after a library
 * sync removed books. The rebuild replaces the rows in one transaction; searches keep using the old vocabulary
 * until it commits.
 */
@Component
class SpellingVocabulary(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val props: RaggedlibProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val building = AtomicBoolean(false)
    private val again = AtomicBoolean(false)

    @Volatile private var built = false

    /** True once a vocabulary exists; until then nothing is corrected. */
    val ready: Boolean get() = built

    data class Entry(val word: String, val docs: Int)

    /** What the vocabulary depends on besides the passages; a change means a rebuild. */
    private val params: String
        get() = props.search.spelling.let { "simple;len=${vocabularyMinLength()}-${Spelling.MAX_WORD_LENGTH};docs=${it.minWordDocs}" }

    // corrections may be one deletion shorter than the shortest word corrected
    private fun vocabularyMinLength() = (props.search.spelling.minWordLength - 1).coerceAtLeast(2)

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        if (!props.search.spelling.enabled) return
        val state = jdbc.queryForMap(
            """
            select coalesce((select words from search_vocabulary_meta), 0) as words,
                   coalesce((select params from search_vocabulary_meta), '') as params,
                   exists (select 1 from vector_store) as has_passages,
                   coalesce((select max(indexed_at) from books), '-infinity')
                       > coalesce((select built_at from search_vocabulary_meta), '-infinity') as newer_books
            """.trimIndent(),
            emptyMap<String, Any>(),
        )
        val words = (state["words"] as Number).toInt()
        val hasPassages = state["has_passages"] == true
        built = words > 0 && hasPassages && state["params"] == params
        val reason = when {
            !hasPassages -> if (words > 0) "the library has no passages" else null
            words == 0 -> "not built yet"
            state["params"] != params -> "built with other settings"
            state["newer_books"] == true -> "older than the last indexed book"
            else -> null
        }
        if (reason != null) rebuildAsync(reason)
    }

    @EventListener
    fun onIngestionFinished(e: IngestionFinished) {
        if (props.search.spelling.enabled && e.chunks > 0) rebuildAsync("indexing stored ${e.chunks} passages")
    }

    @EventListener
    fun onBooksRemoved(e: BooksRemoved) {
        if (props.search.spelling.enabled && e.count > 0) rebuildAsync("${e.count} books were removed from the library")
    }

    /** Starts a rebuild in the background; one requested during a rebuild runs after it. */
    fun rebuildAsync(reason: String) {
        again.set(true)
        if (!building.compareAndSet(false, true)) return
        Thread.ofVirtual().name("spelling-vocabulary").start {
            var why = reason
            // re-check after releasing the flag: a request arriving between the last check and the release would
            // otherwise find the flag still set, leave it to us, and be lost
            do {
                try {
                    while (again.getAndSet(false)) {
                        rebuild(why)
                        why = "passages changed during the last rebuild"
                    }
                } finally {
                    building.set(false)
                }
            } while (again.get() && building.compareAndSet(false, true))
        }
    }

    private fun rebuild(reason: String) {
        val s = props.search.spelling
        log.info("Building the spelling vocabulary ({})", reason)
        val started = System.nanoTime()
        try {
            val words = tx.execute {
                // a second backend on the same database would otherwise build it at the same time
                jdbc.jdbcTemplate.execute("select pg_advisory_xact_lock($ADVISORY_LOCK_KEY)")
                jdbc.jdbcTemplate.execute("delete from search_vocabulary")
                val n = jdbc.update(
                    """
                    insert into search_vocabulary (word, ndoc)
                    select word, ndoc from ts_stat('select to_tsvector(''simple'', content) from vector_store')
                    where ndoc >= :minDocs and word ~ :pattern
                    """.trimIndent(),
                    MapSqlParameterSource("minDocs", s.minWordDocs)
                        .addValue("pattern", "^[[:alpha:]]{${vocabularyMinLength()},${Spelling.MAX_WORD_LENGTH}}$"),
                )
                jdbc.update(
                    """
                    insert into search_vocabulary_meta (id, built_at, words, params) values (true, now(), :n, :params)
                    on conflict (id) do update set built_at = excluded.built_at, words = excluded.words, params = excluded.params
                    """.trimIndent(),
                    MapSqlParameterSource("n", n).addValue("params", params),
                )
                n
            } ?: 0
            jdbc.jdbcTemplate.execute("analyze search_vocabulary")
            built = words > 0
            log.info("Spelling vocabulary built: {} words in {} s", words, (System.nanoTime() - started) / 1_000_000_000)
        } catch (e: Exception) {
            log.warn("Building the spelling vocabulary failed: {}", e.message)
        }
    }

    /** Vocabulary entries among [words] (lower case). */
    fun lookup(words: Collection<String>): List<Entry> {
        if (words.isEmpty()) return emptyList()
        return jdbc.query(
            "select word, ndoc from search_vocabulary where word = any(:words)",
            MapSqlParameterSource("words", words.toTypedArray()),
        ) { rs, _ -> Entry(rs.getString(1), rs.getInt(2)) }
    }

    /**
     * Up to [limit] entries sharing trigrams with [word], most similar first, within two letters of its length:
     * candidates two edits away. Two edits leave few trigrams intact ("tarnsaciton" vs "transaction": 0.20), so
     * the pg_trgm threshold is lowered from its default 0.3 for this query.
     */
    fun similar(word: String, limit: Int): List<Entry> = tx.execute {
        jdbc.jdbcTemplate.execute("set local pg_trgm.similarity_threshold = $SIMILARITY_THRESHOLD")
        jdbc.query(
            """
            select word, ndoc from search_vocabulary
            where word % :w and length(word) between :minLen and :maxLen
            order by word <-> :w
            limit :limit
            """.trimIndent(),
            MapSqlParameterSource("w", word).addValue("limit", limit)
                .addValue("minLen", word.length - 2).addValue("maxLen", word.length + 2),
        ) { rs, _ -> Entry(rs.getString(1), rs.getInt(2)) }
    } ?: emptyList()

    private companion object {
        const val ADVISORY_LOCK_KEY = 7_242_001L
        const val SIMILARITY_THRESHOLD = 0.15
    }
}
