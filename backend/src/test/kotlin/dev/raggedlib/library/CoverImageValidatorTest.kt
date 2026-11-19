package dev.raggedlib.library

import dev.raggedlib.config.ApiException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoverImageValidatorTest {
    @Test
    fun `accepts decoded JPEG and PNG images`() {
        listOf("jpeg", "png").forEach { format ->
            val output = ByteArrayOutputStream()
            assertTrue(ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), format, output))
            val info = CoverImageValidator.inspect(output.toByteArray())
            assertEquals(2, info.width)
            assertEquals(3, info.height)
            assertEquals(if (format == "jpeg") "image/jpeg" else "image/png", info.mediaType)
        }
    }

    @Test
    fun `accepts WebP images`() {
        val webp = Base64.getDecoder().decode(
            "UklGRjwAAABXRUJQVlA4IDAAAADQAQCdASoCAAMAAgA0JaACdLoB+AADsAD+8MQL/yC5YXXI1/8gP+QH/ID/+PIAAAA=",
        )
        CoverImageValidator.validate(webp)
    }

    @Test
    fun `rejects data that is not an image`() {
        assertFailsWith<ApiException> { CoverImageValidator.validate("not an image".toByteArray()) }
    }

    @Test
    fun `rejects images that exceed byte and pixel limits`() {
        assertFailsWith<ApiException> {
            CoverImageValidator.validate(ByteArray(CoverImageValidator.MAX_BYTES + 1))
        }

        val output = ByteArrayOutputStream()
        assertTrue(ImageIO.write(BufferedImage(10_001, 1, BufferedImage.TYPE_INT_RGB), "png", output))
        assertFailsWith<ApiException> { CoverImageValidator.validate(output.toByteArray()) }
    }
}
