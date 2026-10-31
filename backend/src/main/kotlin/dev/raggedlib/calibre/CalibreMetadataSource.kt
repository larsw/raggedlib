package dev.raggedlib.calibre

import dev.raggedlib.config.MetadataSource
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.extract.ProcessRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class CalibreSnapshot(val records: List<CalibreRecord>, val source: String)

@Component
class CalibreMetadataSource(
    private val props: RaggedlibProperties,
    mapper: ObjectMapper,
    private val processRunner: ProcessRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val parser = CalibredbParser(mapper)

    fun load(): CalibreSnapshot = when (props.metadataSource) {
        MetadataSource.CALIBREDB -> CalibreSnapshot(viaCalibredb(), "calibredb")
        MetadataSource.SQLITE -> CalibreSnapshot(viaSqlite(), "metadata.db")
        MetadataSource.AUTO -> try {
            CalibreSnapshot(viaCalibredb(), "calibredb")
        } catch (e: Exception) {
            log.warn("calibredb failed ({}); falling back to reading metadata.db read-only", e.message?.lineSequence()?.firstOrNull())
            CalibreSnapshot(viaSqlite(), "metadata.db")
        }
    }

    private fun viaCalibredb(): List<CalibreRecord> {
        val result = processRunner.run(
            listOf(
                props.tools.calibredb, "list",
                "--library-path", props.libraryPath.toString(),
                "--for-machine",
                "--fields", "id,title,authors,formats,tags,languages,pubdate,series,series_index,cover,last_modified",
            ),
            props.tools.calibredbTimeout,
        )
        if (result.exitCode != 0) {
            error("calibredb exited with ${result.exitCode}: ${result.stderr.take(300)}")
        }
        return parser.parse(result.stdout, props.libraryPath)
    }

    private fun viaSqlite(): List<CalibreRecord> = MetadataDbReader(props.libraryPath).read()
}
