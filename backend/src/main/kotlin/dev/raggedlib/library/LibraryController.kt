package dev.raggedlib.library

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class LibraryController(private val registry: LibraryRegistry, private val books: BookRepository) {
    @GetMapping("/libraries")
    fun libraries(): List<Library> {
        // registry.all first: it backfills pre-existing books' library_id on first access, which the count must see
        val infos = registry.all
        val counts = books.countsByLibrary()
        return infos.map { Library(it.id, it.name, it.root.toString(), counts[it.id] ?: 0) }
    }
}
