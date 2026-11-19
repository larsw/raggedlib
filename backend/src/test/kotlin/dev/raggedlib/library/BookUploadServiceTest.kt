package dev.raggedlib.library

import dev.raggedlib.config.ApiException
import org.springframework.mock.web.MockMultipartFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BookUploadServiceTest {
    @Test
    fun `groups same-basename formats without using client paths`() {
        val groups = groupUploadFiles(
            listOf(
                MockMultipartFile("files", "C:\\books\\Foo.epub", "application/epub+zip", byteArrayOf(1)),
                MockMultipartFile("files", "../foo.PDF", "application/pdf", byteArrayOf(2)),
                MockMultipartFile("files", "Other.txt", "text/plain", byteArrayOf(3)),
            ),
            maxBytes = 10,
            maxSizeLabel = "10 B",
        )

        assertEquals(2, groups.size)
        assertEquals("Foo", groups[0].name)
        assertEquals(listOf("Foo.epub", "foo.PDF"), groups[0].files.map { it.name })
        assertEquals("Other", groups[1].name)
    }

    @Test
    fun `rejects invalid formats duplicate group formats and oversized files`() {
        val unsupported = MockMultipartFile("files", "script.exe", "application/octet-stream", byteArrayOf(1))
        assertFailsWith<ApiException> { groupUploadFiles(listOf(unsupported), 10, "10 B") }

        val duplicates = listOf(
            MockMultipartFile("files", "Book.epub", "application/epub+zip", byteArrayOf(1)),
            MockMultipartFile("files", "book.EPUB", "application/epub+zip", byteArrayOf(2)),
        )
        assertFailsWith<ApiException> { groupUploadFiles(duplicates, 10, "10 B") }

        val oversized = MockMultipartFile("files", "Book.pdf", "application/pdf", byteArrayOf(1, 2))
        assertFailsWith<ApiException> { groupUploadFiles(listOf(oversized), 1, "1 B") }
    }
}
