package dev.raggedlib.calibre

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import tools.jackson.databind.ObjectMapper

class CalibreMetadataLookupScriptTest {
    @Test
    fun `Calibre 9 18 lookup script returns every candidate in the API contract`() {
        val version = runCatching {
            val process = ProcessBuilder("calibre-debug", "--version").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() == 0) output.trim() else ""
        }.getOrDefault("")
        assumeTrue(version.contains("calibre 9.18", ignoreCase = true), "Calibre 9.18 is required for this contract test")

        val script = resource("/calibre/metadata_lookup.py")
        val runner = resource("/calibre/metadata_lookup_contract_test.py")
        val request = Files.createTempFile("raggedlib-metadata-request-", ".json")
        try {
            Files.writeString(
                request,
                """{"title":"The Example","authors":["A. Writer"],"identifiers":{"isbn":"9780000000001"}}""",
            )
            val process = ProcessBuilder(
                "calibre-debug", "-e", runner.toString(), "--", script.toString(), request.toString(),
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), output)
            val jsonLine = output.lineSequence().last { it.trimStart().startsWith("[") }
            val candidates = ObjectMapper().readTree(jsonLine)

            assertEquals(2, candidates.size(), candidates.toString())
            assertEquals("Stub Source", candidates[0].path("source").asString())
            assertEquals("The Example", candidates[0].path("title").asString())
            assertEquals("Example Press", candidates[0].path("publisher").asString())
            assertEquals("2020-05-06", candidates[0].path("publishedDate").asString())
            assertEquals("9780000000002", candidates[0].path("identifiers").path("isbn").asString())
            assertEquals("eng", candidates[0].path("languages")[0].asString())
            assertEquals("Fiction", candidates[0].path("tags")[0].asString())
            assertEquals("A plain description.\n\nSecond paragraph.", candidates[0].path("description").asString())
            assertEquals("Another Stub", candidates[1].path("source").asString())
            assertTrue(candidates[1].path("publisher").isNull)
            assertTrue(candidates[1].path("publishedDate").isNull)
            assertTrue(candidates[1].path("description").isNull)
            assertEquals(0, candidates[1].path("identifiers").size())

            Files.writeString(
                request,
                """{"title":"The Example","authors":["A. Writer"],"identifiers":{"isbn":"9780000000001"},"mode":"covers"}""",
            )
            val coverProcess = ProcessBuilder(
                "calibre-debug", "-e", runner.toString(), "--", script.toString(), request.toString(),
            ).redirectErrorStream(true).start()
            val coverOutput = coverProcess.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, coverProcess.waitFor(), coverOutput)
            val coverJson = coverOutput.lineSequence().last { it.trimStart().startsWith("{") }
            val coverResult = ObjectMapper().readTree(coverJson)
            val covers = coverResult.path("candidates")
            val coverSources = covers.values().map { it.path("source").asString() }.toSet()

            assertFalse(coverResult.path("timedOut").asBoolean())
            assertEquals(0, coverResult.path("failedSources").size())
            assertEquals(2, covers.size(), coverResult.toString())
            assertEquals(setOf("Stub Cover Source A", "Stub Cover Source B"), coverSources)
            assertTrue(
                covers.values().all { Base64.getDecoder().decode(it.path("image").asString()).startsWithPngSignature() },
                coverResult.toString(),
            )
        } finally {
            Files.deleteIfExists(request)
        }
    }

    private fun resource(path: String): Path = Path.of(javaClass.getResource(path)!!.toURI())

    private fun ByteArray.startsWithPngSignature() =
        size >= 8 && copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
}
