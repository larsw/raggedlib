package dev.raggedlib.vectors

import dev.raggedlib.config.badRequest
import dev.raggedlib.library.LibraryRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class VectorSpaceController(private val service: VectorSpaceService, private val libraries: LibraryRegistry) {
    @GetMapping("/vectors")
    fun vectors(
        @RequestParam libraryId: Long,
        @RequestParam(defaultValue = "passages") level: String,
        @RequestParam(defaultValue = "umap") method: String,
        @RequestParam(defaultValue = "1000") size: Int,
        /** a new sample (Reshuffle); default: the library's current one */
        @RequestParam(required = false) seed: Long?,
    ): VectorSpace {
        val m = runCatching { VectorMethod.valueOf(method.uppercase()) }
            .getOrElse { badRequest("method must be one of pca, umap, tsne") }
        val l = runCatching { VectorLevel.valueOf(level.uppercase()) }
            .getOrElse { badRequest("level must be passages or books") }
        if (size !in 1..VectorSpaceService.MAX_SIZE) badRequest("size must be between 1 and ${VectorSpaceService.MAX_SIZE}")
        // one library at a time; byId 404s an unknown one, and (first use) backfills pre-multi-library books' library_id
        libraries.byId(libraryId)
        return service.space(libraryId, l, m, size, seed)
    }

    /**
     * Computes book vectors (the average of a book's passage embeddings) for books of [libraryId] (default: every
     * library) that have passages but no vector yet, e.g. indexed before book vectors existed. No re-indexing.
     */
    @PostMapping("/vectors/book-vectors")
    fun computeBookVectors(@RequestParam(required = false) libraryId: Long?): BookVectorsResult {
        libraryId?.let(libraries::byId)
        val computed = service.computeBookVectors(libraryId)
        return BookVectorsResult(computed, service.missingBookVectors(libraryId))
    }
}

/** [computed] book vectors; [missing]: books still without one (normally 0). */
data class BookVectorsResult(val computed: Int, val missing: Int)
