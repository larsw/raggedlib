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
import java.util.Properties
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
    fun `removes pending uploads made more than 24 hours ago`() {
        val (store, directory) = retainOne()
        setCreatedAt(directory, Instant.now().minus(Duration.ofHours(25)))

        store.cleanupExpired()

        assertFalse(Files.exists(directory))
    }

    @Test
    fun `goes by the stored upload time, not the directory's`() {
        val (store, directory) = retainOne()
        Files.setLastModifiedTime(directory, FileTime.from(Instant.now().minus(Duration.ofHours(25))))

        store.cleanupExpired()

        assertTrue(Files.exists(directory))
    }

    @Test
    fun `removes a pending upload whose manifest is unreadable once the directory is old`() {
        val (store, directory) = retainOne()
        Files.delete(directory.resolve(PendingBookUploadStore.MANIFEST))
        store.cleanupExpired()
        assertTrue(Files.exists(directory))

        Files.setLastModifiedTime(directory, FileTime.from(Instant.now().minus(Duration.ofHours(25))))
        store.cleanupExpired()
        assertFalse(Files.exists(directory))
    }

    private fun retainOne(): Pair<PendingBookUploadStore, Path> {
        val source = Files.createDirectory(root.resolve("source"))
        Files.write(source.resolve("Book.epub"), byteArrayOf(1))
        val store = PendingBookUploadStore(RaggedlibProperties(tmpDir = root))
        val id = store.retain(
            7,
            source,
            listOf(UploadFile(MockMultipartFile("files", "Book.epub", "application/epub+zip", byteArrayOf(1)), "Book.epub", "EPUB")),
        )
        return store to root.resolve("raggedlib-upload-pending").resolve(id)
    }

    private fun setCreatedAt(directory: Path, at: Instant) {
        val path = directory.resolve(PendingBookUploadStore.MANIFEST)
        val manifest = Properties().apply { Files.newInputStream(path).use(::load) }
        manifest.setProperty("createdAt", at.toEpochMilli().toString())
        Files.newOutputStream(path).use { manifest.store(it, null) }
    }
}
