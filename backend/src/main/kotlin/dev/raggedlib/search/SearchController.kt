package dev.raggedlib.search

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class SearchController(private val search: SearchService) {
    @PostMapping("/search")
    fun search(@RequestBody req: SearchRequest): List<SourceChunk> = search.search(req)
}
