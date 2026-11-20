package dev.raggedlib.library

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.LibraryRole
import dev.raggedlib.config.ApiException
import dev.raggedlib.config.badRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/activity")
class BookActivityController(
    private val access: Access,
    private val activity: BookActivityRepository,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) libraryId: Long?,
        @RequestParam(required = false) user: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): BookActivityPage {
        val administered = access.libraries(LibraryRole.ADMIN)
        if (administered.isEmpty()) throw ApiException(HttpStatus.FORBIDDEN, "only a library admin can view activity")
        val libraryIds = if (libraryId == null) administered else {
            setOf(access.requireLibrary(libraryId, LibraryRole.ADMIN).id)
        }
        val normalizedAction = action?.let { requested ->
            BookActivityAction.entries.firstOrNull { it.name == requested.uppercase() }
                ?: badRequest("action must be one of ${BookActivityAction.entries.map { it.name }.sorted().joinToString()}")
        }
        return activity.list(
            libraryIds,
            user?.takeIf { it.isNotBlank() },
            normalizedAction,
            page.coerceAtLeast(0),
            size.coerceIn(1, MAX_PAGE_SIZE),
        )
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
