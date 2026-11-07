package dev.raggedlib.search

import dev.raggedlib.auth.Access
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class SearchController(private val search: SearchService, private val access: Access) {
    /** Searches the libraries the caller can see (narrowed by [SearchRequest.libraryIds]). */
    @PostMapping("/search")
    fun search(@RequestBody req: SearchRequest): SearchResponse = search.search(req, access.libraries())
}
