package dev.raggedlib.mcp

import dev.raggedlib.chat.ChatRequest
import dev.raggedlib.chat.ChatService
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.ingest.IngestService
import dev.raggedlib.library.BookRepository
import dev.raggedlib.library.BookStatus
import dev.raggedlib.search.ScoreKind
import dev.raggedlib.search.SearchRequest
import dev.raggedlib.search.SearchService
import dev.raggedlib.search.SourceChunk
import dev.raggedlib.search.SpellingCorrection
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * MCP tools over the library: passage search, grounded answers, and book/tag listings so an agent can narrow a
 * search to specific books. All read-only; nothing here starts indexing or changes the library.
 */
@Component
class LibraryTools(
    private val search: SearchService,
    private val chat: ChatService,
    private val books: BookRepository,
    private val ingest: IngestService,
    private val props: RaggedlibProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Passage(
        val ref: Int,
        val bookId: Long,
        val title: String,
        val authors: List<String>,
        val page: Int?,
        val section: String?,
        /** 0..1 reranker relevance (comparable across searches) when [scoreKind] is RERANK. */
        val score: Double,
        val scoreKind: ScoreKind,
        val text: String,
    )

    data class SearchResult(
        val query: String,
        /** What was searched for instead, when misspelled words were corrected; null otherwise. */
        val correctedQuery: String?,
        val passages: List<Passage>,
        val note: String?,
    )

    data class AnswerResult(
        val answer: String,
        val sources: List<Passage>,
        /** The query the sources were retrieved with: a rewritten follow-up, spell-corrected. */
        val searchQuery: String,
        /** Words of the question corrected for retrieval (the model still answers the question as asked). */
        val corrections: List<SpellingCorrection>,
        val model: String,
        val elapsedMs: Long,
    )

    data class BookInfo(
        val id: Long,
        val title: String,
        val authors: List<String>,
        val tags: List<String>,
        val languages: List<String>,
        val pubYear: Int?,
        val status: BookStatus,
        val chunks: Int,
    )

    data class BookList(val total: Long, val page: Int, val size: Int, val books: List<BookInfo>)

    data class TagInfo(val name: String, val books: Int)

    data class LibraryStatus(
        val totalBooks: Int,
        val booksByStatus: Map<BookStatus, Int>,
        val totalPassages: Long,
        val indexingRunning: Boolean,
        val note: String,
    )

    @McpTool(
        name = "search_library",
        title = "Search the ebook library",
        description = """Find passages in the user's ebook library (mostly technical books) that match a query.
Hybrid vector + full-text search, re-scored by a cross-encoder: passages that aren't really relevant are dropped,
so an empty result means the indexed books don't cover the topic. No LLM involved; fast (about 1 s).
Each passage has the book id/title/authors and a page (PDFs) or section (ebooks) to cite.
Use list_books or list_tags first to restrict a search to particular books or tags.""",
        annotations = McpAnnotations(title = "Search the ebook library", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun searchLibrary(
        @McpToolParam(description = "What to look for: a question, topic or exact terms. Supports \"quoted phrases\", -exclusions and OR.")
        query: String,
        @McpToolParam(description = "Number of passages to return (1-50, default 8).", required = false)
        topK: Int?,
        @McpToolParam(description = "Only search these books (ids from list_books).", required = false)
        bookIds: List<Long>?,
        @McpToolParam(description = "Only search books having ANY of these tags (names from list_tags).", required = false)
        tags: List<String>?,
        @McpToolParam(description = "Cross-encoder reranking with a relevance cutoff (default true). false = raw hybrid order, never empty while anything matches.", required = false)
        rerank: Boolean?,
        @McpToolParam(description = "Correct misspelled words (default true): words the library lacks, or has only rarely next to a far more common spelling. false searches for the query exactly as written.", required = false)
        correctSpelling: Boolean?,
    ): SearchResult {
        log.info("MCP search_library: '{}'", query.take(200))
        val response = search.search(SearchRequest(query, topK, bookIds, tags, rerank, correctSpelling))
        val found = response.results.map(::passage)
        // MCP clients cap tool output (Claude Code: ~25K tokens); keep whole passages up to a character budget
        var used = 0
        val passages = found.takeWhile { used += it.text.length + 300; used <= MAX_OUTPUT_CHARS }.ifEmpty { found.take(1) }
        val notes = listOfNotNull(
            response.correctedQuery?.let { c -> "Searched for \"$c\": ${correctionNote(response.corrections)}" },
            when {
                found.isEmpty() -> "No passage in the indexed books was relevant enough. Try other terms, " +
                    "drop book/tag filters, or check library_status: only indexed books are searchable."
                passages.size < found.size -> "Showing the best ${passages.size} of ${found.size} passages (output size limit); " +
                    "narrow the search with bookIds or tags to see others."
                else -> null
            },
        )
        return SearchResult(query, response.correctedQuery, passages, notes.joinToString(" ").ifEmpty { null })
    }

    @McpTool(
        name = "ask_library",
        title = "Ask the ebook library",
        description = """Answer a question from the user's ebook library: retrieves the relevant passages and has a local
LLM write an answer grounded only in them, citing them as [1], [2]... which map to the returned sources.
If the books don't cover the question, the answer says so. Slow: typically 20-90 s on the local GPU, cut off
(partial answer) after about 150 s, so give this tool a timeout of at least 180 s. Sources come back as short
excerpts; use search_library to read passages in full, or to synthesise an answer yourself.""",
        annotations = McpAnnotations(title = "Ask the ebook library", readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = false),
    )
    fun askLibrary(
        @McpToolParam(description = "The question, self-contained (no conversation context is kept between calls).")
        question: String,
        @McpToolParam(description = "Passages to give the model as context (default 8; more are trimmed to what fits its context, about 10).", required = false)
        topK: Int?,
        @McpToolParam(description = "Only use these books (ids from list_books).", required = false)
        bookIds: List<Long>?,
        @McpToolParam(description = "Only use books having ANY of these tags.", required = false)
        tags: List<String>?,
        @McpToolParam(description = "Chat model override (an installed Ollama model); default is the server's.", required = false)
        model: String?,
        @McpToolParam(description = "Correct misspelled words for retrieval (default true); false retrieves for the question exactly as written.", required = false)
        correctSpelling: Boolean?,
    ): AnswerResult {
        require(question.isNotBlank()) { "question must not be empty" }
        log.info("MCP ask_library: '{}'", question.take(200))
        val req = ChatRequest(query = question, topK = topK, bookIds = bookIds, tags = tags, model = model, correct = correctSpelling)
        val a = try {
            chat.answer(req, props.mcp.answerTimeout)
        } catch (e: Exception) {
            val notFound = generateSequence<Throwable>(e) { it.cause }
                .any { it is WebClientResponseException && it.statusCode.value() == 404 }
            if (notFound && !model.isNullOrBlank()) {
                throw IllegalArgumentException("Unknown model '$model': it isn't installed in Ollama. Omit model to use the " +
                    "server default (${chat.modelFor(req.copy(model = null))}).")
            }
            throw e
        }
        val sources = a.sources.map { s ->
            passage(s).let { p -> if (p.text.length > SOURCE_EXCERPT_CHARS) p.copy(text = p.text.take(SOURCE_EXCERPT_CHARS).trimEnd() + " …") else p }
        }
        return AnswerResult(a.answer, sources, a.searchQuery, a.corrections, a.model, a.elapsedMs)
    }

    @McpTool(
        name = "list_books",
        title = "List books in the library",
        description = """List or find books in the library by title/author substring, tag or indexing status.
Returns ids to pass as bookIds to search_library / ask_library. Only INDEXED books are searchable.""",
        annotations = McpAnnotations(title = "List books", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun listBooks(
        @McpToolParam(description = "Substring of the title or an author's name (case-insensitive).", required = false)
        query: String?,
        @McpToolParam(description = "Only books with this tag (exact name, see list_tags).", required = false)
        tag: String?,
        @McpToolParam(description = "Only books in this status: PENDING, INDEXED, FAILED, SKIPPED, EXTRACTING or EMBEDDING.", required = false)
        status: String?,
        @McpToolParam(description = "Page number, from 0 (default 0).", required = false)
        page: Int?,
        @McpToolParam(description = "Page size (1-100, default 25).", required = false)
        size: Int?,
    ): BookList {
        val st = status?.takeIf { it.isNotBlank() }?.let { s ->
            BookStatus.entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown status '$s'; use one of ${BookStatus.entries.joinToString()}")
        }
        val p = books.search(query, st, tag, (page ?: 0).coerceAtLeast(0), (size ?: 25).coerceIn(1, 100), "title")
        return BookList(p.total, p.page, p.size, p.items.map { b ->
            BookInfo(b.id, b.title, b.authors, b.tags, b.languages, b.pubdate?.atZone(java.time.ZoneOffset.UTC)?.year,
                b.status, b.chunkCount)
        })
    }

    @McpTool(
        name = "list_tags",
        title = "List tags",
        description = "List the Calibre tags in the library with how many books carry each, most used first. Use them to restrict searches by topic. There are many rare tags; use limit to see more.",
        annotations = McpAnnotations(title = "List tags", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun listTags(
        @McpToolParam(description = "Maximum number of tags (most used first; default 100, max 5000).", required = false)
        limit: Int?,
    ): List<TagInfo> = books.tags().take((limit ?: 100).coerceIn(1, 5000)).map { TagInfo(it.name, it.count) }

    @McpTool(
        name = "library_status",
        title = "Library status",
        description = "How much of the library is indexed (searchable): book counts by status, total passages, and whether indexing is running.",
        annotations = McpAnnotations(title = "Library status", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun libraryStatus(): LibraryStatus {
        val s = ingest.status()
        val indexed = s.counts[BookStatus.INDEXED] ?: 0
        return LibraryStatus(s.totalBooks, s.counts, s.totalChunks, s.running,
            "$indexed of ${s.totalBooks} books are indexed and searchable.")
    }

    private companion object {
        /** Total passage text per search_library result, well under common MCP client output limits. */
        const val MAX_OUTPUT_CHARS = 60_000
        /** ask_library returns sources as excerpts of this length; the answer cites them by ref. */
        const val SOURCE_EXCERPT_CHARS = 400
    }

    private fun correctionNote(corrections: List<SpellingCorrection>): String =
        corrections.joinToString("; ") { c ->
            if (c.known) "'${c.from}' -> '${c.to}' ('${c.from}' is rare in the library, '${c.to}' far more common)"
            else "'${c.from}' -> '${c.to}' ('${c.from}' occurs nowhere in the library)"
        } + ". Pass correctSpelling=false to search for the query as written."

    private fun passage(c: SourceChunk) =
        Passage(c.ref, c.bookId, c.title, c.authors, c.page, c.section, c.score, c.scoreKind, c.text)
}
