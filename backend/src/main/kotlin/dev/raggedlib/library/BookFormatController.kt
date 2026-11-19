package dev.raggedlib.library

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/books/{id}/formats")
class BookFormatController(private val formats: BookFormatService) {
    @PutMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun add(
        @PathVariable id: Long,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("replace", defaultValue = "false") replace: Boolean,
    ): BookDetails = formats.add(id, file, replace)

    @DeleteMapping("/{format}")
    fun remove(@PathVariable id: Long, @PathVariable format: String): BookDetails = formats.remove(id, format)
}
