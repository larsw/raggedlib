package dev.raggedlib.library

import org.springframework.stereotype.Component
import java.nio.file.Path

/** Resolves library-relative paths (as stored in the DB) against their owning library's root, safely. */
@Component
class LibraryPaths(private val registry: LibraryRegistry) {
    fun resolve(libraryId: Long, relative: String): Path {
        val root = registry.byId(libraryId).root
        val p = Path.of(relative)
        val resolved = (if (p.isAbsolute) p else root.resolve(p)).normalize()
        require(resolved.startsWith(root)) { "path escapes library root" }
        return resolved
    }
}
