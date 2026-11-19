package dev.raggedlib.calibre

import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.extract.ProcessRunner
import dev.raggedlib.library.LibraryInfo
import org.springframework.stereotype.Component
import java.nio.file.Path

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
