package dev.raggedlib.library

import dev.raggedlib.config.badRequest
import dev.raggedlib.config.ApiException
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

object CoverImageValidator {
    const val MAX_BYTES = 10 * 1024 * 1024
    private const val MAX_DIMENSION = 10_000
    private const val MAX_PIXELS = 16_000_000L
    private val FORMATS = setOf("jpeg", "jpg", "png", "webp")

    fun validate(data: ByteArray) {
        if (data.isEmpty()) badRequest("cover image is empty")
        if (data.size > MAX_BYTES) badRequest("cover image must be no larger than 10 MB")

        val input = ImageIO.createImageInputStream(ByteArrayInputStream(data))
            ?: badRequest("cover must be a JPEG, PNG, or WebP image")
        input.use {
            val reader = ImageIO.getImageReaders(it).asSequence().firstOrNull()
                ?: badRequest("cover must be a JPEG, PNG, or WebP image")
            try {
                reader.input = it
                if (reader.formatName.lowercase() !in FORMATS) {
                    badRequest("cover must be a JPEG, PNG, or WebP image")
                }
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION ||
                    width.toLong() * height > MAX_PIXELS
                ) {
                    badRequest("cover image dimensions must be at most 10,000 pixels per side and 16 megapixels")
                }
                if (reader.read(0) == null) badRequest("cover image is invalid")
            } catch (e: Exception) {
                if (e is ApiException) throw e
                badRequest("cover must be a valid JPEG, PNG, or WebP image")
            } finally {
                reader.dispose()
            }
        }
    }
}
