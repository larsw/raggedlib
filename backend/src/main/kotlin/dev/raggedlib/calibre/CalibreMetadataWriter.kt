package dev.raggedlib.calibre

import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.extract.ProcessRunner
import dev.raggedlib.library.LibraryInfo
import org.springframework.stereotype.Component
import java.nio.file.Path

private const val CALIBRE_DUPLICATE_NOTICE = "The following books were not added as they already exist in the database"

internal fun parseAddedCalibreBookId(stdout: String, stderr: String): Long? {
    val added = Regex("""(?m)^Added book ids:\s*(\d+(?:\s*,\s*\d+)*)\s*$""")
        .find(stdout)
        ?.groupValues
        ?.get(1)
    if (added != null) {
        val ids = added.split(',').map { it.trim().toLongOrNull() ?: error("calibredb returned an invalid book id: $it") }
        check(ids.size == 1) { "calibredb added ${ids.size} books for one upload group" }
        return ids.single()
    }
    if (CALIBRE_DUPLICATE_NOTICE in stderr) return null
    error("calibredb add returned neither an added book id nor a duplicate result: ${stderr.take(500)}${stdout.take(500)}")
}

@Component
class CalibreMetadataWriter(
    private val props: RaggedlibProperties,
    private val processRunner: ProcessRunner,
    private val writeLock: CalibreLibraryWriteLock,
) {
    fun setFields(library: LibraryInfo, calibreId: Long, fields: Map<String, String>) =
        writeLock.withLibrary(library.root) {
            val command = buildList {
                addAll(listOf(props.tools.calibredb, "set_metadata", calibreId.toString(), "--library-path", library.root.toString()))
                fields.forEach { (name, value) -> addAll(listOf("--field", "$name:$value")) }
            }
            val result = processRunner.run(command, props.tools.calibredbTimeout)
            check(result.exitCode == 0) {
                "calibredb set_metadata failed for book $calibreId in library '${library.name}' " +
                    "(${result.exitCode}): ${result.stderr.take(500)}"
            }
        }

    fun setCover(library: LibraryInfo, calibreId: Long, coverFile: Path?) =
        writeLock.withLibrary(library.root) {
            val result = processRunner.run(
                listOf(props.tools.calibreDebug, "-c", SET_COVER_SCRIPT),
                props.tools.calibredbTimeout,
                env = buildMap {
                    put("RAGGEDLIB_LIBRARY_PATH", library.root.toString())
                    put("RAGGEDLIB_CALIBRE_ID", calibreId.toString())
                    coverFile?.let { put("RAGGEDLIB_COVER_PATH", it.toString()) }
                },
            )
            check(result.exitCode == 0) {
                "Calibre failed to update cover for book $calibreId in library '${library.name}' " +
                    "(${result.exitCode}): ${result.stderr.take(500)}"
            }
        }

    fun addFormat(library: LibraryInfo, calibreId: Long, file: Path, replace: Boolean) =
        writeLock.withLibrary(library.root) {
            val command = buildList {
                addAll(listOf(props.tools.calibredb, "add_format", "--library-path", library.root.toString()))
                if (!replace) add("--dont-replace")
                addAll(listOf(calibreId.toString(), file.toString()))
            }
            val result = processRunner.run(command, props.tools.calibredbTimeout)
            check(result.exitCode == 0) {
                "calibredb add_format failed for book $calibreId in library '${library.name}' " +
                    "(${result.exitCode}): ${result.stderr.take(500)}"
            }
        }

    /** Adds one staged directory as a single logical book; null means Calibre refused a duplicate. */
    fun addBook(library: LibraryInfo, directory: Path, allowDuplicates: Boolean = false): Long? = writeLock.withLibrary(library.root) {
        val result = processRunner.run(
            buildList {
                addAll(listOf(props.tools.calibredb, "add", "--library-path", library.root.toString()))
                if (allowDuplicates) add("--duplicates")
                addAll(listOf("--one-book-per-directory", "--recurse", directory.toString()))
            },
            props.tools.calibredbTimeout,
        )
        check(result.exitCode == 0) {
            "calibredb add failed for library '${library.name}' (${result.exitCode}): ${result.stderr.take(500)}"
        }
        parseAddedCalibreBookId(result.stdout, result.stderr)
    }

    fun removeFormat(library: LibraryInfo, calibreId: Long, format: String) =
        writeLock.withLibrary(library.root) {
            val result = processRunner.run(
                listOf(
                    props.tools.calibredb, "remove_format", calibreId.toString(), format,
                    "--library-path", library.root.toString(),
                ),
                props.tools.calibredbTimeout,
            )
            check(result.exitCode == 0) {
                "calibredb remove_format failed for book $calibreId in library '${library.name}' " +
                    "(${result.exitCode}): ${result.stderr.take(500)}"
            }
        }

    private companion object {
        val SET_COVER_SCRIPT = """
            import os
            from calibre.library import db as library_db

            cover_path = os.environ.get("RAGGEDLIB_COVER_PATH")
            cover_data = open(cover_path, "rb").read() if cover_path else None
            library_db(os.environ["RAGGEDLIB_LIBRARY_PATH"]).new_api.set_cover(
                {int(os.environ["RAGGEDLIB_CALIBRE_ID"]): cover_data}
            )
        """.trimIndent()
    }
}
