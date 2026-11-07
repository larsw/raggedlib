package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.LibraryRole
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class LibraryController(private val registry: LibraryRegistry, private val books: BookRepository, private val access: Access) {
    /** The libraries the caller can see, with its role in each; the path on disk only for admins. */
    @GetMapping("/libraries")
    fun libraries(): List<Library> {
        // registry.all first: it backfills pre-existing books' library_id on first access, which the count must see
        val infos = registry.all
        val roles = access.roles()
        val admin = access.isAdmin()
        val counts = books.countsByLibrary()
        return infos.mapNotNull { lib ->
            val role = roles[lib.id]?.takeIf { it.atLeast(LibraryRole.VIEWER) } ?: return@mapNotNull null
            Library(lib.id, lib.name, if (admin) lib.root.toString() else null, counts[lib.id] ?: 0, role)
        }
    }
}
