package dev.raggedlib.calibre

import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.extract.ProcessRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration

/**
 * Whether a library flagged writable can really be written by raggedlib through calibredb. Calibre itself needs to
 * create files in the library directory (it probes case sensitivity there, and adds books, covers and OPF backups),
 * write metadata.db, and understand its schema: an older calibredb than the one that last wrote the library could
 * misread what it doesn't know. Pure, for testing; [CalibreSchema] supplies the supported version.
 */
object LibraryWriteCheck {
    /** Why [root] can't be written, or null if it can. [supportedSchema] null = unknown (no calibre-debug). */
    fun readOnlyReason(root: Path, supportedSchema: Int?): String? {
        if (!Files.isDirectory(root)) return "library directory $root not found"
        val probe = root.resolve(".raggedlib-write-test")
        try {
            Files.deleteIfExists(probe)
            Files.createFile(probe)
            Files.delete(probe)
        } catch (e: Exception) {
            return "can't create files in $root (mounted read-only, or owned by another user?): ${e.javaClass.simpleName}"
        }
        val db = root.resolve("metadata.db")
        if (!Files.isRegularFile(db)) return "no metadata.db in $root"
        if (!Files.isWritable(db)) return "metadata.db isn't writable by this user"
        val version = try {
            schemaVersion(db)
        } catch (e: Exception) {
            return "can't read metadata.db's schema version: ${e.message}"
        }
        if (supportedSchema == null) return "can't tell which Calibre schema calibredb supports (is calibre-debug installed?)"
        if (version > supportedSchema) {
            return "metadata.db has schema version $version, newer than the $supportedSchema the installed Calibre knows; " +
                "upgrade Calibre in raggedlib to the version that maintains this library"
        }
        return null
    }

    fun schemaVersion(db: Path): Int {
        Class.forName("org.sqlite.JDBC")
        val uri = db.toAbsolutePath().toUri().rawPath
        return DriverManager.getConnection("jdbc:sqlite:file:$uri?mode=ro").use { c ->
            c.createStatement().use { s -> s.executeQuery("pragma user_version").use { rs -> rs.next(); rs.getInt(1) } }
        }
    }
}

/** The newest metadata.db schema version the installed Calibre can handle, asked of Calibre itself, once. */
@Component
class CalibreSchema(private val props: RaggedlibProperties, private val processRunner: ProcessRunner) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Calibre upgrades a library with `upgrade_version_N` (from N to N+1) steps; the newest step + 1 is the version it
     * writes. Null if calibre-debug isn't available or answers unexpectedly.
     */
    val supportedVersion: Int? by lazy {
        try {
            val r = processRunner.run(
                listOf(props.tools.calibreDebug, "-c", SCRIPT),
                Duration.ofSeconds(30),
            )
            r.stdout.trim().lines().lastOrNull()?.trim()?.toIntOrNull()?.plus(1)
                ?: null.also { log.warn("calibre-debug didn't report a schema version (exit {}): {}", r.exitCode, r.stderr.take(300)) }
        } catch (e: Exception) {
            log.warn("Couldn't run calibre-debug to find the supported Calibre schema: {}", e.message)
            null
        }
    }

    private companion object {
        const val SCRIPT = "from calibre.db.schema_upgrades import SchemaUpgrade as S; " +
            "print(max(int(m.rsplit('_', 1)[1]) for m in dir(S) if m.startswith('upgrade_version_')))"
    }
}
