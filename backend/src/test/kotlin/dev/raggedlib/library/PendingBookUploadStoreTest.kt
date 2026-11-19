package dev.raggedlib.library

import dev.raggedlib.config.ApiException
import dev.raggedlib.config.RaggedlibProperties
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PendingBookUploadStoreTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `retains uploads only for their library until discarded`() {
        val source = Files.createDirectory(root.resolve("source"))
        Files.write(source.resolve("Book.epub"), byteArrayOf(1, 2, 3))
        val store = PendingBookUploadStore(RaggedlibProperties(tmpDir = root))

        val id = store.retain(
            7,
            source,
            listOf(
                UploadFile(MockMultipartFile("files", "Book.epub", "application/epub+zip", byteArrayOf(1)), "Book.epub", "EPUB"),
            ),
        )

        val pending = store.get(7, id)
        assertEquals(listOf("Book.epub"), pending.files.map { it.name })
        assertTrue(Files.exists(pending.files.single().path))
        assertFailsWith<ApiException> { store.get(8, id) }

        store.discard(id)
        assertFalse(Files.exists(pending.files.single().path))
        assertFailsWith<ApiException> { store.get(7, id) }
    }

    @Test
    fun `removes pending upload directories older than 24 hours`() {
        val source = Files.createDirectory(root.resolve("source"))
        Files.write(source.resolve("Book.epub"), byteArrayOf(1))
        val store = PendingBookUploadStore(RaggedlibProperties(tmpDir = root))
        val id = store.retain(
            7,
            source,
            listOf(UploadFile(MockMultipartFile("files", "Book.epub", "application/epub+zip", byteArrayOf(1)), "Book.epub", "EPUB")),
        )
        val directory = root.resolve("raggedlib-upload-pending").resolve(id)
        Files.setLastModifiedTime(directory, FileTime.from(Instant.now().minus(Duration.ofHours(25))))

        store.cleanupExpired()

        assertFalse(Files.exists(directory))
    }
}
