package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.LibraryRole
import dev.raggedlib.calibre.CalibreLibraryWriteLock
import dev.raggedlib.config.ApiException
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LibraryEditingRequest(val paused: Boolean)
data class LibraryEditingView(val editingPaused: Boolean)

@RestController
@RequestMapping("/api/libraries")
class LibraryEditingController(
    private val access: Access,
    private val registry: LibraryRegistry,
    private val sync: LibraryService,
    private val writeLock: CalibreLibraryWriteLock,
    private val activity: BookActivityRepository,
    private val tx: TransactionTemplate,
) {
    @PutMapping("/{id}/editing")
    fun setEditingPaused(@PathVariable id: Long, @RequestBody request: LibraryEditingRequest): LibraryEditingView {
        val authorized = access.requireLibrary(id, LibraryRole.ADMIN)
        if (!authorized.writable) registry.requireEditingAllowed(id)

        return writeLock.withLibrary(authorized.root) {
            val library = access.requireLibrary(id, LibraryRole.ADMIN)
            if (!library.writable) registry.requireEditingAllowed(id)
            if (library.editingPaused == request.paused) return@withLibrary LibraryEditingView(request.paused)

            if (!request.paused) {
                val result = sync.sync(setOf(id))
                if (result.source == "none") {
                    throw ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "library sync failed; editing remains paused")
                }
            }

            val caller = access.caller()
            val actor = caller.userId?.let { access.user().label } ?: caller.label
            try {
                tx.executeWithoutResult {
                    registry.setEditingPaused(id, request.paused)
                    activity.recordLibraryAction(
                        id,
                        actor,
                        if (request.paused) "PAUSE_EDITING" else "RESUME_EDITING",
                    )
                }
            } catch (e: Exception) {
                registry.invalidate()
                throw e
            }
            LibraryEditingView(request.paused)
        }
    }
}
