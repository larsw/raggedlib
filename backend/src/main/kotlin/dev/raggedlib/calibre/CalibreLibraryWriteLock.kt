package dev.raggedlib.calibre

import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes Calibre writes per normalized library path. */
@Component
class CalibreLibraryWriteLock {
    private val locks = ConcurrentHashMap<Path, ReentrantLock>()

    fun <T> withLibrary(root: Path, action: () -> T): T =
        locks.computeIfAbsent(root.toAbsolutePath().normalize()) { ReentrantLock() }.withLock(action)
}
