package dev.raggedlib.library

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LibraryEditingRequest(val paused: Boolean)

@RestController
@RequestMapping("/api/libraries")
class LibraryEditingController(private val editing: LibraryEditingService) {
    @PutMapping("/{id}/editing")
    fun setEditingPaused(@PathVariable id: Long, @RequestBody request: LibraryEditingRequest): LibraryEditingView =
        editing.setEditingPaused(id, request.paused)
}
