package dev.raggedlib.library

import dev.raggedlib.config.ApiException
import org.springframework.mock.web.MockMultipartFile
import dev.raggedlib.config.label
import org.springframework.util.unit.DataSize
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
            DataSize.ofBytes(10),
        )

        assertEquals(2, groups.size)
        assertEquals("Foo", groups[0].name)
        assertEquals(listOf("Foo.epub", "foo.PDF"), groups[0].files.map { it.name })
        assertEquals("Other", groups[1].name)
    }

    @Test
    fun `rejects invalid formats duplicate group formats and oversized files`() {
        val unsupported = MockMultipartFile("files", "script.exe", "application/octet-stream", byteArrayOf(1))
        assertFailsWith<ApiException> { groupUploadFiles(listOf(unsupported), DataSize.ofBytes(10)) }

        val duplicates = listOf(
            MockMultipartFile("files", "Book.epub", "application/epub+zip", byteArrayOf(1)),
            MockMultipartFile("files", "book.EPUB", "application/epub+zip", byteArrayOf(2)),
        )
        assertFailsWith<ApiException> { groupUploadFiles(duplicates, DataSize.ofBytes(10)) }

        val oversized = MockMultipartFile("files", "Book.pdf", "application/pdf", byteArrayOf(1, 2))
        val tooLarge = assertFailsWith<ApiException> { groupUploadFiles(listOf(oversized), DataSize.ofBytes(1)) }
        assertEquals("file 'Book.pdf' is larger than the 1 bytes limit", tooLarge.message)
    }

    @Test
    fun `labels sizes in whole or decimal units`() {
        assertEquals("500 MB", DataSize.ofMegabytes(500).label())
        assertEquals("2 GB", DataSize.ofGigabytes(2).label())
        assertEquals("1.5 GB", DataSize.ofMegabytes(1536).label())
        assertEquals("10 KB", DataSize.ofKilobytes(10).label())
        assertEquals("12 bytes", DataSize.ofBytes(12).label())
    }
}
