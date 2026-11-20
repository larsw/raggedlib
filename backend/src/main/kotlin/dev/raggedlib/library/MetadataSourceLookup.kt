package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.ApiScope
import dev.raggedlib.calibre.CalibreMetadataSource
import dev.raggedlib.config.ApiException
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.config.badRequest
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

data class MetadataLookupRequest(
    val title: String?,
    val authors: List<String>,
    val identifiers: Map<String, String>,
    val mode: String = "metadata",
)

data class MetadataCoverCandidate(
    val source: String,
    val width: Int,
    val height: Int,
    val mediaType: String,
    val image: ByteArray,
)

data class MetadataCoverLookupResult(
    val candidates: List<MetadataCoverCandidate>,
    val timedOut: Boolean,
    val failedSources: List<String>,
)

private data class MetadataCoverPayload(val source: String, val image: String)

private data class MetadataCoverPayloadResult(
    val candidates: List<MetadataCoverPayload>,
    val timedOut: Boolean,
    val failedSources: List<String>,
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
    fun candidates(bookId: Long): List<MetadataCandidate> = lookup(lookupRequest(bookId))

    fun candidates(title: String?, authors: List<String>?, isbn: String?): List<MetadataCandidate> {
        requireLookupEnabled()
        access.requireScope(ApiScope.LIBRARY_EDIT)

        val queryTitle = title?.trim()?.takeIf(String::isNotEmpty)
        val queryIsbn = isbn?.trim()?.takeIf(String::isNotEmpty)
        if (queryTitle == null && queryIsbn == null) badRequest("provide a title or ISBN for metadata lookup")
        val identifiers = queryIsbn?.let { mapOf("isbn" to it) }.orEmpty()
        val queryAuthors = authors.orEmpty().map(String::trim).filter(String::isNotEmpty)
        return lookup(MetadataLookupRequest(queryTitle, queryAuthors, identifiers))
    }

    private fun lookup(request: MetadataLookupRequest): List<MetadataCandidate> =
        mapper.readValue<List<MetadataCandidate>>(runLookup(request))

    fun coverCandidates(bookId: Long): MetadataCoverLookupResult {
        val result = mapper.readValue<MetadataCoverPayloadResult>(
            runLookup(lookupRequest(bookId).copy(mode = "covers")),
        )
        val failedSources = result.failedSources.toMutableSet()
        val candidates = result.candidates.mapNotNull { candidate ->
            if (candidate.image.length > MAX_ENCODED_COVER_LENGTH) {
                failedSources += candidate.source
                return@mapNotNull null
            }
            val image = try {
                java.util.Base64.getDecoder().decode(candidate.image)
            } catch (e: IllegalArgumentException) {
                throw ApiException(HttpStatus.BAD_GATEWAY, "Calibre cover lookup returned invalid image data")
            }
            val info = try {
                CoverImageValidator.inspect(image)
            } catch (e: ApiException) {
                failedSources += candidate.source
                return@mapNotNull null
            }
            MetadataCoverCandidate(candidate.source, info.width, info.height, info.mediaType, image)
        }
        return MetadataCoverLookupResult(candidates, result.timedOut, failedSources.sorted())
    }

    private fun lookupRequest(bookId: Long): MetadataLookupRequest {
        requireLookupEnabled()
        val (authorized, library) = access.requireEditableBook(bookId)
        val identifiers = calibre.extras(library.root, authorized.book.calibreId)?.identifiers.orEmpty()
        return MetadataLookupRequest(authorized.book.title, authorized.book.authors, identifiers)
    }

    private fun requireLookupEnabled() {
        if (!settings.metadataLookupEnabled) {
            throw ApiException(HttpStatus.FORBIDDEN, "metadata downloads are disabled by the administrator")
        }
    }

    private fun runLookup(request: MetadataLookupRequest): String {
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
                val type = if (request.mode == "covers") "cover lookup" else "metadata lookup"
                throw ApiException(HttpStatus.GATEWAY_TIMEOUT, "$type timed out after 30 seconds; try again")
            }
            if (result.exitCode != 0) {
                val type = if (request.mode == "covers") "cover lookup" else "metadata lookup"
                throw ApiException(HttpStatus.BAD_GATEWAY, "Calibre $type failed: ${result.stderr.take(500)}")
            }
            return result.stdout.lineSequence().lastOrNull {
                it.trimStart().startsWith("[") || it.trimStart().startsWith("{")
            } ?: throw ApiException(HttpStatus.BAD_GATEWAY, "Calibre lookup returned no candidate data")
        } finally {
            Files.deleteIfExists(requestFile)
            Files.deleteIfExists(scriptFile)
        }
    }

    private companion object {
        val LOOKUP_TIMEOUT: Duration = Duration.ofSeconds(30)
        const val MAX_ENCODED_COVER_LENGTH = (CoverImageValidator.MAX_BYTES * 4L / 3L + 4L).toInt()
    }
}

@RestController
@RequestMapping("/api/books")
class MetadataSourceLookupController(private val lookup: MetadataSourceLookupService) {
    @PostMapping("/{id}/metadata-candidates")
    fun candidates(@PathVariable id: Long): List<MetadataCandidate> = lookup.candidates(id)

    @PostMapping("/{id}/cover-candidates")
    fun coverCandidates(@PathVariable id: Long): MetadataCoverLookupResult = lookup.coverCandidates(id)
}
