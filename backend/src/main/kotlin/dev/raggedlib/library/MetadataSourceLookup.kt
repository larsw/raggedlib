package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.ApiScope
import dev.raggedlib.calibre.CalibreMetadataSource
import dev.raggedlib.config.ApiException
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.extract.ProcessRunner
import dev.raggedlib.extract.ProcessTimeoutException
import dev.raggedlib.settings.AppSettings
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.time.Duration

data class MetadataCandidate(
    val source: String,
    val title: String,
    val authors: List<String>,
    val publisher: String?,
    val publishedDate: String?,
    val identifiers: Map<String, String>,
    val languages: List<String>,
    val tags: List<String>,
    val description: String?,
)

class MetadataLookupRequest(
    val title: String,
    val authors: List<String>,
    val identifiers: Map<String, String>,
)

@Service
class MetadataSourceLookupService(
    private val access: Access,
    private val calibre: CalibreMetadataSource,
    private val settings: AppSettings,
    private val props: RaggedlibProperties,
    private val processRunner: ProcessRunner,
    private val mapper: ObjectMapper,
) {
    fun candidates(bookId: Long): List<MetadataCandidate> {
        if (!settings.metadataLookupEnabled) {
            throw ApiException(HttpStatus.FORBIDDEN, "metadata downloads are disabled by the administrator")
        }
        access.requireScope(ApiScope.LIBRARY_EDIT)
        val authorized = access.requireEditableBook(bookId)
        val library = access.requireEditableLibrary(authorized.book.libraryId)
        val identifiers = calibre.extras(library.root, authorized.book.calibreId)?.identifiers.orEmpty()
        val request = MetadataLookupRequest(authorized.book.title, authorized.book.authors, identifiers)

        Files.createDirectories(props.tmpDir)
        val requestFile = Files.createTempFile(props.tmpDir, "raggedlib-metadata-request-", ".json")
        val scriptFile = Files.createTempFile(props.tmpDir, "raggedlib-metadata-lookup-", ".py")
        try {
            Files.writeString(requestFile, mapper.writeValueAsString(request))
            ClassPathResource("calibre/metadata_lookup.py").inputStream.use { input ->
                Files.copy(input, scriptFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            val result = try {
                processRunner.run(
                    listOf(props.tools.calibreDebug, "-e", scriptFile.toString(), "--", requestFile.toString()),
                    LOOKUP_TIMEOUT,
                )
            } catch (e: ProcessTimeoutException) {
                throw ApiException(HttpStatus.GATEWAY_TIMEOUT, "metadata lookup timed out after 30 seconds; try again")
            }
            if (result.exitCode != 0) {
                throw ApiException(HttpStatus.BAD_GATEWAY, "Calibre metadata lookup failed: ${result.stderr.take(500)}")
            }
            val json = result.stdout.lineSequence().lastOrNull { it.trimStart().startsWith("[") }
                ?: throw ApiException(HttpStatus.BAD_GATEWAY, "Calibre metadata lookup returned no candidate data")
            return mapper.readValue<List<MetadataCandidate>>(json)
        } finally {
            Files.deleteIfExists(requestFile)
            Files.deleteIfExists(scriptFile)
        }
    }

    private companion object {
        val LOOKUP_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

@RestController
@RequestMapping("/api/books")
class MetadataSourceLookupController(private val lookup: MetadataSourceLookupService) {
    @PostMapping("/{id}/metadata-candidates")
    fun candidates(@PathVariable id: Long): List<MetadataCandidate> = lookup.candidates(id)
}
