package dev.raggedlib.vectors

import dev.raggedlib.config.badRequest
import dev.raggedlib.library.LibraryRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class VectorSpaceController(private val service: VectorSpaceService, private val libraries: LibraryRegistry) {
    @GetMapping("/vectors")
    fun vectors(
        @RequestParam libraryId: Long,
        @RequestParam(defaultValue = "umap") method: String,
        @RequestParam(defaultValue = "1000") size: Int,
        @RequestParam(defaultValue = "1") seed: Long,
    ): VectorSpace {
        val m = runCatching { VectorMethod.valueOf(method.uppercase()) }
            .getOrElse { badRequest("method must be one of pca, umap, tsne") }
        if (size !in 1..VectorSpaceService.MAX_SIZE) badRequest("size must be between 1 and ${VectorSpaceService.MAX_SIZE}")
        // one library at a time; byId 404s an unknown one, and (first use) backfills pre-multi-library books' library_id
        libraries.byId(libraryId)
        return service.space(libraryId, m, size, seed)
    }
}
