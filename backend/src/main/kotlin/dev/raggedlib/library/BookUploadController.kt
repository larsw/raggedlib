package dev.raggedlib.library

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/libraries/{libraryId}/books")
class BookUploadController(private val uploads: BookUploadService) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun add(
        @PathVariable libraryId: Long,
        @RequestParam("files") files: List<MultipartFile>,
    ): BookUploadResult = uploads.add(libraryId, files)
}
