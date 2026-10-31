package dev.raggedlib.calibre

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path

/** Parses `calibredb list --for-machine` JSON output. */
class CalibredbParser(private val mapper: ObjectMapper) {

    fun parse(json: String, libraryRoot: Path): List<CalibreRecord> {
        val root = mapper.readTree(json)
        require(root.isArray) { "calibredb output is not a JSON array" }
        val lib = libraryRoot.toAbsolutePath().normalize()
        return root.values().map { node -> toRecord(node, lib) }
    }

    private fun toRecord(n: JsonNode, lib: Path): CalibreRecord {
        val formats = linkedMapOf<String, String>()
        n.path("formats").forEach { f ->
            val p = f.asString()
            val ext = p.substringAfterLast('.', "").uppercase()
            if (ext.isNotEmpty() && ext !in formats) formats[ext] = relativize(lib, p)
        }
        val authors = when {
            n.path("authors").isArray -> n.path("authors").values().map { it.asString() }
            else -> textOrNull(n, "authors")?.split(" & ")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        }
        return CalibreRecord(
            id = n.path("id").asLong(),
            title = textOrNull(n, "title") ?: "Unknown",
            authors = authors,
            tags = n.path("tags").values().map { it.asString() },
            series = textOrNull(n, "series"),
            seriesIndex = if (textOrNull(n, "series") != null && n.path("series_index").isNumber) n.path("series_index").asDouble() else null,
            languages = n.path("languages").values().map { it.asString() },
            pubdate = CalibreDates.pubdate(textOrNull(n, "pubdate")),
            formats = formats,
            coverPath = textOrNull(n, "cover")?.let { relativize(lib, it) },
            lastModified = CalibreDates.lastModified(textOrNull(n, "last_modified")),
        )
    }

    private fun textOrNull(n: JsonNode, field: String): String? {
        val v = n.get(field) ?: return null
        if (v.isNull || v.isMissingNode) return null
        return v.asString().takeIf { it.isNotBlank() }
    }

    companion object {
        fun relativize(lib: Path, absolute: String): String {
            val p = Path.of(absolute).toAbsolutePath().normalize()
            val rel = if (p.startsWith(lib)) lib.relativize(p) else p
            return rel.toString().replace('\\', '/')
        }
    }
}
