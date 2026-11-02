package dev.raggedlib.settings

import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.config.badRequest
import dev.raggedlib.ingest.IngestService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** GET/PUT /api/settings: server settings that can be changed while running. */
data class SettingsView(
    /** Books extracted at the same time while indexing (embedding stays one GPU queue). */
    val parallelBooks: Int,
    val parallelBooksDefault: Int,
    val parallelBooksMax: Int,
)

data class SettingsUpdate(val parallelBooks: Int? = null)

/**
 * Runtime settings, stored in `app_settings` so they survive restarts; a setting never changed falls back to the
 * configuration (`raggedlib.ingest.extract-concurrency` for [parallelBooks]). Reads are cached in memory.
 */
@Component
class AppSettings(private val jdbc: NamedParameterJdbcTemplate, private val props: RaggedlibProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Upper bound for [parallelBooks]: one extraction per core at most (each runs an external converter). */
    val parallelBooksMax: Int = props.ingest.maxExtractConcurrency.takeIf { it > 0 }
        ?: Runtime.getRuntime().availableProcessors().coerceIn(1, 16)

    val parallelBooksDefault: Int get() = props.ingest.extractConcurrency.coerceIn(1, parallelBooksMax)

    @Volatile private var cachedParallelBooks: Int? = null

    val parallelBooks: Int
        get() = cachedParallelBooks ?: (read(PARALLEL_BOOKS)?.toIntOrNull()?.coerceIn(1, parallelBooksMax) ?: parallelBooksDefault)
            .also { cachedParallelBooks = it }

    fun setParallelBooks(n: Int) {
        if (n !in 1..parallelBooksMax) badRequest("parallelBooks must be between 1 and $parallelBooksMax")
        write(PARALLEL_BOOKS, n.toString())
        cachedParallelBooks = n
        log.info("Books indexed in parallel set to {}", n)
    }

    fun view() = SettingsView(parallelBooks, parallelBooksDefault, parallelBooksMax)

    private fun read(key: String): String? = jdbc.queryForList(
        "select value from app_settings where key = :key", mapOf("key" to key), String::class.java,
    ).firstOrNull()

    private fun write(key: String, value: String) {
        jdbc.update(
            """
            insert into app_settings (key, value, updated_at) values (:key, :value, now())
            on conflict (key) do update set value = excluded.value, updated_at = excluded.updated_at
            """.trimIndent(),
            mapOf("key" to key, "value" to value),
        )
    }

    private companion object {
        const val PARALLEL_BOOKS = "ingest.parallelBooks"
    }
}

@RestController
@RequestMapping("/api/settings")
class SettingsController(private val settings: AppSettings, private val ingest: IngestService) {
    @GetMapping
    fun get(): SettingsView = settings.view()

    @PutMapping
    fun put(@RequestBody body: SettingsUpdate): SettingsView {
        body.parallelBooks?.let {
            settings.setParallelBooks(it)
            ingest.parallelBooksChanged()
        }
        return settings.view()
    }
}
