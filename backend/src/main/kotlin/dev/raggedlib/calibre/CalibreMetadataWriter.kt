package dev.raggedlib.calibre

import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.extract.ProcessRunner
import dev.raggedlib.library.LibraryInfo
import org.springframework.stereotype.Component

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
}
