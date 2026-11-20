package dev.raggedlib.library

import java.nio.file.Files
import java.nio.file.Path

/** Deletes [directory] and everything under it; nothing happens if it doesn't exist. */
internal fun deleteTree(directory: Path) {
    if (!Files.exists(directory)) return
    Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
