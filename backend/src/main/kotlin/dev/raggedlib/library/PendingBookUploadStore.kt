package dev.raggedlib.library

import dev.raggedlib.calibre.Formats
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.config.notFound
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class PendingUploadFile(val name: String, val format: String, val path: Path)

data class PendingBookUpload(val id: String, val libraryId: Long, val files: List<PendingUploadFile>)

@Component
@EnableScheduling
class PendingBookUploadStore(private val props: RaggedlibProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val root = props.tmpDir.resolve("raggedlib-upload-pending")
    private val decisionLocks = ConcurrentHashMap<String, ReentrantLock>()

    internal fun retain(libraryId: Long, source: Path, files: List<UploadFile>): String {
        Files.createDirectories(root)
        val id = UUID.randomUUID().toString()
        val temporary = root.resolve(".$id.tmp")
        val destination = root.resolve(id)
        Files.createDirectory(temporary)
        try {
            Files.move(source, temporary.resolve("files"))
            val manifest = Properties().apply {
                setProperty("libraryId", libraryId.toString())
                setProperty("createdAt", Instant.now().toEpochMilli().toString())
                setProperty("fileCount", files.size.toString())
                files.forEachIndexed { index, file ->
                    setProperty("file.$index.name", file.name)
                    setProperty("file.$index.format", file.format)
                }
            }
            Files.newOutputStream(temporary.resolve("manifest.properties")).use { manifest.store(it, null) }
            Files.move(temporary, destination)
            return id
        } catch (e: Exception) {
            deleteTree(temporary)
            throw e
        }
    }

    fun get(libraryId: Long, id: String): PendingBookUpload {
        val uuid = parseId(id)
        val directory = root.resolve(uuid.toString())
        val manifestPath = directory.resolve("manifest.properties")
        if (!Files.isRegularFile(manifestPath)) notFound("pending upload not found or expired")
        val manifest = Properties().apply {
            Files.newInputStream(manifestPath).use(::load)
        }
        val storedLibraryId = manifest.getProperty("libraryId")?.toLongOrNull()
            ?: notFound("pending upload not found or expired")
        val createdAt = manifest.getProperty("createdAt")?.toLongOrNull()
            ?.let(Instant::ofEpochMilli) ?: notFound("pending upload not found or expired")
        if (storedLibraryId != libraryId) notFound("pending upload not found or expired")
        if (createdAt.isBefore(Instant.now().minus(RETENTION))) {
            deleteTree(directory)
            decisionLocks.remove(uuid.toString())
            notFound("pending upload not found or expired")
        }
        val count = manifest.getProperty("fileCount")?.toIntOrNull()
            ?.takeIf { it > 0 } ?: notFound("pending upload not found or expired")
        val filesDirectory = directory.resolve("files")
        val files = (0 until count).map { index ->
            val name = manifest.getProperty("file.$index.name")
                ?.takeIf { it.isNotBlank() && Path.of(it).fileName.toString() == it }
                ?: notFound("pending upload not found or expired")
            val format = manifest.getProperty("file.$index.format")
                ?.takeIf { it.matches(Regex("[A-Z0-9]{1,10}")) }
                ?: notFound("pending upload not found or expired")
            if (Formats.fromFilename(name) != format) notFound("pending upload not found or expired")
            val path = filesDirectory.resolve(name)
            if (!Files.isRegularFile(path)) notFound("pending upload not found or expired")
            PendingUploadFile(name, format, path)
        }
        return PendingBookUpload(uuid.toString(), storedLibraryId, files)
    }

    fun <T> withPending(libraryId: Long, id: String, action: (PendingBookUpload) -> T): T {
        val uuid = parseId(id).toString()
        if (!Files.isRegularFile(root.resolve(uuid).resolve("manifest.properties"))) {
            notFound("pending upload not found or expired")
        }
        val lock = decisionLocks.computeIfAbsent(uuid) { ReentrantLock() }
        return lock.withLock { action(get(libraryId, uuid)) }
    }

    fun discard(id: String) {
        val uuid = parseId(id).toString()
        deleteTree(root.resolve(uuid))
        decisionLocks.remove(uuid)
    }

    @EventListener(ApplicationReadyEvent::class)
    fun cleanOnStartup() = cleanupExpired()

    @Scheduled(fixedDelay = 60_000)
    fun cleanupExpired() {
        if (!Files.isDirectory(root)) return
        val cutoff = Instant.now().minus(RETENTION)
        Files.list(root).use { children ->
            children.filter(Files::isDirectory).forEach { directory ->
                val name = directory.fileName.toString()
                if (name.startsWith(".")) {
                    if (Files.getLastModifiedTime(directory).toInstant().isBefore(cutoff)) {
                        deleteTree(directory)
                        log.info("Removed abandoned pending upload {}", directory.fileName)
                    }
                } else {
                    val uuid = try {
                        UUID.fromString(name).toString()
                    } catch (_: IllegalArgumentException) {
                        log.warn("Removing invalid pending upload directory {}", directory.fileName)
                        deleteTree(directory)
                        return@forEach
                    }
                    val lock = decisionLocks.computeIfAbsent(uuid) { ReentrantLock() }
                    lock.withLock {
                        if (!Files.exists(directory)) return@withLock
                        if (Files.getLastModifiedTime(directory).toInstant().isBefore(cutoff)) {
                            deleteTree(directory)
                            decisionLocks.remove(uuid, lock)
                            log.info("Removed expired pending upload {}", directory.fileName)
                        }
                    }
                }
            }
        }
    }

    private fun parseId(id: String): UUID = try {
        UUID.fromString(id)
    } catch (_: IllegalArgumentException) {
        notFound("pending upload not found or expired")
    }

    private fun deleteTree(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        val RETENTION: Duration = Duration.ofHours(24)
    }
}
