package dev.raggedlib.mcp

import dev.raggedlib.auth.Access
import dev.raggedlib.chat.ChatService
import dev.raggedlib.config.ApiException
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.ingest.IngestService
import dev.raggedlib.library.BookMetadataService
import dev.raggedlib.library.BookRepository
import dev.raggedlib.library.LibraryRegistry
import dev.raggedlib.library.MetadataCandidate
import dev.raggedlib.library.MetadataSourceLookupService
import dev.raggedlib.search.SearchService
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchBookMetadataToolTest {
    @Test
    fun `title query returns candidates and no results as readable statuses`() {
        val lookup = Mockito.mock(MetadataSourceLookupService::class.java)
        val candidate = MetadataCandidate(
            source = "Stub Source",
            title = "The Example",
            authors = listOf("A. Writer"),
            publisher = "Example Press",
            publishedDate = null,
            identifiers = mapOf("isbn" to "9780000000001"),
            languages = emptyList(),
            tags = emptyList(),
            description = null,
        )
        Mockito.`when`(lookup.candidates(null, listOf("A. Writer"), "9780000000001")).thenReturn(listOf(candidate))
        Mockito.`when`(lookup.candidates("Missing", null, null)).thenReturn(emptyList())
        val tools = tools(lookup)

        val found = tools.fetchBookMetadata(null, null, listOf("A. Writer"), "9780000000001")
        assertEquals("found", found.status)
        assertEquals(candidate, found.candidates.single())

        val missing = tools.fetchBookMetadata(null, "Missing", null, null)
        assertEquals("no_results", missing.status)
        assertEquals(emptyList(), missing.candidates)
        assertTrue(missing.message.contains("No metadata candidates"))
    }

    @Test
    fun `a lookup timeout is returned as a readable status`() {
        val lookup = Mockito.mock(MetadataSourceLookupService::class.java)
        Mockito.`when`(lookup.candidates("Slow", null, null))
            .thenThrow(ApiException(HttpStatus.GATEWAY_TIMEOUT, "metadata lookup timed out after 30 seconds; try again"))

        val result = tools(lookup).fetchBookMetadata(null, "Slow", null, null)

        assertEquals("timeout", result.status)
        assertEquals(emptyList(), result.candidates)
        assertTrue(result.message.contains("timed out"))
    }

    private fun tools(lookup: MetadataSourceLookupService) = LibraryTools(
        search = Mockito.mock(SearchService::class.java),
        chat = Mockito.mock(ChatService::class.java),
        books = Mockito.mock(BookRepository::class.java),
        ingest = Mockito.mock(IngestService::class.java),
        props = Mockito.mock(RaggedlibProperties::class.java),
        libraries = Mockito.mock(LibraryRegistry::class.java),
        access = Mockito.mock(Access::class.java),
        metadata = Mockito.mock(BookMetadataService::class.java),
        mapper = ObjectMapper(),
        metadataLookup = lookup,
    )
}
