package dev.raggedlib.library

import dev.raggedlib.config.RaggedlibProperties
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Resolves library-relative paths (as stored in the DB) against the configured library root, safely. */
@Component
class LibraryPaths(props: RaggedlibProperties) {
    val root: Path = props.libraryPath.toAbsolutePath().normalize()

    fun resolve(relative: String): Path {
        val p = Path.of(relative)
        val resolved = (if (p.isAbsolute) p else root.resolve(p)).normalize()
        require(resolved.startsWith(root)) { "path escapes library root" }
        return resolved
    }
}
