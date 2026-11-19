package dev.raggedlib.calibre

import dev.raggedlib.config.MetadataSource
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.extract.ProcessRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class CalibreSnapshot(val records: List<CalibreRecord>, val source: String)

data class CalibreBookIdentity(val title: String, val authors: List<String>)

internal fun parseCalibreBookIdentity(opf: String, fallbackTitle: String): CalibreBookIdentity {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        setExpandEntityReferences(false)
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val document = factory.newDocumentBuilder().parse(opf.byteInputStream())
    val title = document.getElementsByTagNameNS("*", "title").item(0)?.textContent?.trim()
        ?.takeIf { it.isNotEmpty() } ?: fallbackTitle
    val creators = document.getElementsByTagNameNS("*", "creator")
    val authors = (0 until creators.length).mapNotNull { index ->
        val creator = creators.item(index)
        val role = creator.attributes?.getNamedItemNS("http://www.idpf.org/2007/opf", "role")?.nodeValue
            ?: creator.attributes?.getNamedItem("role")?.nodeValue
        val name = creator.textContent.trim()
        name.takeIf { name.isNotEmpty() && (role.isNullOrBlank() || role.equals("aut", ignoreCase = true)) }
    }.ifEmpty { listOf("Unknown") }
    return CalibreBookIdentity(title, authors)
}

@Component
class CalibreMetadataSource(
    private val props: RaggedlibProperties,
    mapper: ObjectMapper,
    private val processRunner: ProcessRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val parser = CalibredbParser(mapper)

    fun load(libraryRoot: Path): CalibreSnapshot = when (props.metadataSource) {
        MetadataSource.CALIBREDB -> CalibreSnapshot(viaCalibredb(libraryRoot), "calibredb")
        MetadataSource.SQLITE -> CalibreSnapshot(viaSqlite(libraryRoot), "metadata.db")
        MetadataSource.AUTO -> try {
            CalibreSnapshot(viaCalibredb(libraryRoot), "calibredb")
        } catch (e: Exception) {
            log.warn("calibredb failed ({}); falling back to reading metadata.db read-only", e.message?.lineSequence()?.firstOrNull())
            CalibreSnapshot(viaSqlite(libraryRoot), "metadata.db")
        }
    }

    /** Reads one book back from Calibre after a write. */
    fun find(libraryRoot: Path, calibreId: Long): CalibreRecord? =
        viaCalibredb(libraryRoot, "id:$calibreId").singleOrNull()

    /** Reads Calibre-only editable fields from its database, without opening it for writing. */
    fun extras(libraryRoot: Path, calibreId: Long): CalibreExtras? = MetadataDbReader(libraryRoot).extras(calibreId)

    fun identity(file: Path, fallbackTitle: String): CalibreBookIdentity {
        Files.createDirectories(props.tmpDir)
        val opf = Files.createTempFile(props.tmpDir, "raggedlib-upload-metadata-", ".opf")
        try {
            val result = processRunner.run(
                listOf(props.tools.ebookMeta, file.toString(), "--to-opf", opf.toString()),
                props.tools.convertTimeout,
            )
            check(result.exitCode == 0) {
                "ebook-meta failed to read upload metadata (${result.exitCode}): ${result.stderr.take(500)}"
            }
            return parseCalibreBookIdentity(Files.readString(opf), fallbackTitle)
        } finally {
            Files.deleteIfExists(opf)
        }
    }

    private fun viaCalibredb(libraryRoot: Path, search: String? = null): List<CalibreRecord> {
        val command = buildList {
            addAll(
                listOf(
                    props.tools.calibredb, "list",
                    "--library-path", libraryRoot.toString(),
                    "--for-machine",
                    "--fields", "id,title,authors,formats,tags,languages,pubdate,series,series_index,cover,last_modified,publisher",
                ),
            )
            if (search != null) addAll(listOf("--search", search))
        }
        val result = processRunner.run(
            command,
            props.tools.calibredbTimeout,
        )
        if (result.exitCode != 0) {
            error("calibredb exited with ${result.exitCode}: ${result.stderr.take(300)}")
        }
        return parser.parse(result.stdout, libraryRoot)
    }

    private fun viaSqlite(libraryRoot: Path): List<CalibreRecord> = MetadataDbReader(libraryRoot).read()
}
