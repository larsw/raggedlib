package dev.raggedlib.library

import dev.raggedlib.calibre.Formats
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.config.label
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.util.unit.DataSize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** What an upload of book files (new books, or formats of a book) may contain. */
@Component
class UploadLimits(
    props: RaggedlibProperties,
    @Value("\${spring.servlet.multipart.max-request-size:2GB}") val maxRequestSize: DataSize,
) {
    /** Per file; Spring's multipart `max-file-size` is set from the same variable. */
    val maxFormatSize: DataSize = props.upload.maxFormatSize.also {
        require(it.toBytes() > 0) { "raggedlib.upload.max-format-size must be positive" }
    }

    fun options() = UploadOptions(
        formats = Formats.UPLOADABLE.sorted(),
        maxFormatSize = maxFormatSize.toBytes(),
        maxFormatSizeLabel = maxFormatSize.label(),
        maxRequestSize = maxRequestSize.toBytes(),
        maxRequestSizeLabel = maxRequestSize.label(),
    )
}

/** GET /api/upload-options: so the UI checks files the same way before sending them. */
data class UploadOptions(
    val formats: List<String>,
    val maxFormatSize: Long,
    val maxFormatSizeLabel: String,
    val maxRequestSize: Long,
    val maxRequestSizeLabel: String,
)

@RestController
class UploadOptionsController(private val limits: UploadLimits) {
    @GetMapping("/api/upload-options")
    fun options(): UploadOptions = limits.options()
}
