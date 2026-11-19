package dev.raggedlib.library

import dev.raggedlib.config.badRequest
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/books/{id}/cover")
class BookCoverController(private val covers: BookCoverService) {
    @PutMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@PathVariable id: Long, @RequestParam("file") file: MultipartFile): BookDetails {
        if (file.isEmpty) badRequest("choose a cover image")
        if (file.size > CoverImageValidator.MAX_BYTES) badRequest("cover image must be no larger than 10 MB")
        return covers.upload(id, file.bytes)
    }

    @DeleteMapping
    fun remove(@PathVariable id: Long): BookDetails = covers.remove(id)

    @PostMapping("/from-format")
    fun useFormat(@PathVariable id: Long, @RequestParam format: String): BookDetails = covers.useFormat(id, format)
}
