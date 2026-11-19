package dev.raggedlib.library

import dev.raggedlib.calibre.CalibreSchema
import dev.raggedlib.calibre.LibraryWriteCheck
import dev.raggedlib.config.ApiException
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.config.notFound
import org.springframework.http.HttpStatus
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * One configured Calibre library, resolved against its `libraries` row. [writable]: configured writable, and the
 * checks passed (LibraryWriteCheck); [readOnlyReason]: why a library configured writable isn't.
 */
data class LibraryInfo(
    val id: Long,
    val name: String,
    val root: Path,
    val writable: Boolean = false,
    val readOnlyReason: String? = null,
    val editingPaused: Boolean = false,
)

const val EDITING_PAUSED_MESSAGE = "Editing is paused (the library is open in desktop Calibre)"

/**
 * Reconciles the configured libraries ([RaggedlibProperties.effectiveLibraries]) against the `libraries` table,
 * matching by (normalized, absolute) path — the stable identity across restarts and config changes — and backfills
 * books indexed before multi-library support into whichever library matches [RaggedlibProperties.libraryPath].
 *
 * Reconciliation happens lazily, on first use, rather than at bean construction: Flyway's migration (which this
 * depends on) isn't guaranteed to have run yet at that point, but by the time anything actually needs a library
 * (a sync, a search, a file read) the application is fully up. Same pattern as [dev.raggedlib.settings.AppSettings].
 */
@Component
class LibraryRegistry(
    private val props: RaggedlibProperties,
    private val jdbc: NamedParameterJdbcTemplate,
    private val calibreSchema: CalibreSchema,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var cached: List<LibraryInfo>? = null

    val all: List<LibraryInfo> get() = cached ?: reconcile().also { cached = it }

    fun byId(id: Long): LibraryInfo = all.firstOrNull { it.id == id } ?: notFound("library $id not found")

    fun requireEditingAllowed(id: Long): LibraryInfo {
        val library = byId(id)
        if (!library.writable) {
            val reason = library.readOnlyReason?.replace(library.root.toString(), library.name)
            throw ApiException(HttpStatus.FORBIDDEN, reason ?: "library '${library.name}' is read-only")
        }
        if (library.editingPaused) throw ApiException(HttpStatus.FORBIDDEN, EDITING_PAUSED_MESSAGE)
        return library
    }

    @Synchronized
    fun setEditingPaused(id: Long, paused: Boolean): LibraryInfo {
        val library = byId(id)
        if (library.editingPaused == paused) return library
        if (jdbc.update("update libraries set editing_paused = :paused where id = :id", mapOf("id" to id, "paused" to paused)) != 1) {
            notFound("library $id not found")
        }
        cached = cached?.map { if (it.id == id) it.copy(editingPaused = paused) else it }
        return byId(id)
    }

    @Synchronized
    fun invalidate() {
        cached = null
    }

    @Synchronized
    private fun reconcile(): List<LibraryInfo> {
        cached?.let { return it }
        val infos = props.effectiveLibraries().map { cfg ->
            val root = cfg.path.toAbsolutePath().normalize()
            val existingId = jdbc.queryForList(
                "select id from libraries where path = :path", mapOf("path" to root.toString()), Long::class.java,
            ).firstOrNull()
            val id = existingId ?: jdbc.queryForObject(
                "insert into libraries (name, path) values (:name, :path) returning id",
                mapOf("name" to cfg.name, "path" to root.toString()), Long::class.java,
            )!!
            val editingPaused = jdbc.queryForObject(
                "select editing_paused from libraries where id = :id",
                mapOf("id" to id),
                Boolean::class.java,
            ) ?: false
            if (existingId != null) {
                jdbc.update(
                    "update libraries set name = :name where id = :id and name <> :name",
                    mapOf("id" to id, "name" to cfg.name),
                )
            }
            val reason = if (cfg.writable) LibraryWriteCheck.readOnlyReason(root, calibreSchema.supportedVersion) else null
            if (cfg.writable && reason != null) log.warn("Library '{}' is configured writable but stays read-only: {}", cfg.name, reason)
            LibraryInfo(
                id,
                cfg.name,
                root,
                writable = cfg.writable && reason == null,
                readOnlyReason = reason,
                editingPaused = editingPaused,
            )
        }
        backfillLegacyBooks(infos)
        log.info("Libraries: {}", infos.joinToString { "${it.name} (#${it.id}, ${it.root}${if (it.writable) ", writable" else ""})" })
        return infos
    }

    /** Books indexed before multi-library support have no library_id yet; assign them to the one they came from. */
    private fun backfillLegacyBooks(infos: List<LibraryInfo>) {
        val orphans = jdbc.queryForObject(
            "select count(*) from books where library_id is null", emptyMap<String, Any>(), Long::class.java,
        ) ?: 0L
        if (orphans == 0L) return
        // raggedlib.library-path has a default in application.yml, so it's set even for a fresh multi-library
        // config whose path doesn't match it (e.g. a container mount): fall back to the sole configured library.
        val legacyRoot = props.libraryPath?.toAbsolutePath()?.normalize()
        val target = infos.firstOrNull { it.root == legacyRoot } ?: infos.singleOrNull()
        if (target == null) {
            log.warn(
                "{} books predate multi-library support and have no library assigned; set raggedlib.library-path " +
                    "to the library they came from, or configure a single raggedlib.libraries entry, then restart.",
                orphans,
            )
            return
        }
        val updated = jdbc.update(
            "update books set library_id = :id, calibre_id = id where library_id is null",
            MapSqlParameterSource("id", target.id),
        )
        log.info("Assigned {} pre-existing books to library '{}' (#{})", updated, target.name, target.id)
    }
}
