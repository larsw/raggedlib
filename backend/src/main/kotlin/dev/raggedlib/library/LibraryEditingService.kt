package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.LibraryRole
import dev.raggedlib.calibre.CalibreLibraryWriteLock
import dev.raggedlib.config.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

data class LibraryEditingView(val editingPaused: Boolean)

/** Library admins pause editing while a library is open in desktop Calibre, and resume it afterwards. */
@Service
class LibraryEditingService(
    private val access: Access,
    private val registry: LibraryRegistry,
    private val sync: LibraryService,
    private val writeLock: CalibreLibraryWriteLock,
    private val activity: BookActivityRepository,
    private val tx: TransactionTemplate,
) {
    fun setEditingPaused(libraryId: Long, paused: Boolean): LibraryEditingView {
        val authorized = requireWritableAdmin(libraryId)
        return writeLock.withLibrary(authorized.root) {
            // again under the lock: a waiting request may have changed the library meanwhile
            val library = requireWritableAdmin(libraryId)
            if (library.editingPaused == paused) return@withLibrary LibraryEditingView(paused)

            // Resuming reads what desktop Calibre changed first, so edits don't start from stale metadata.
            if (!paused && !sync.sync(setOf(libraryId)).anySynced()) {
                throw ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "library sync failed; editing remains paused")
            }

            try {
                tx.executeWithoutResult {
                    registry.setEditingPaused(libraryId, paused)
                    activity.recordLibraryAction(
                        libraryId,
                        access.activityActor(),
                        if (paused) BookActivityAction.PAUSE_EDITING else BookActivityAction.RESUME_EDITING,
                    )
                }
            } catch (e: Exception) {
                registry.invalidate()
                throw e
            }
            LibraryEditingView(paused)
        }
    }

    /** Library admin of [libraryId]; a library that isn't writable can't be paused or resumed (403, with the reason). */
    private fun requireWritableAdmin(libraryId: Long): LibraryInfo {
        val library = access.requireLibrary(libraryId, LibraryRole.ADMIN)
        if (!library.writable) registry.requireEditingAllowed(libraryId)
        return library
    }
}
