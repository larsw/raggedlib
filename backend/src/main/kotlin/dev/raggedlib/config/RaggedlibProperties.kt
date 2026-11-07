package dev.raggedlib.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

enum class MetadataSource { AUTO, CALIBREDB, SQLITE }

/** All settings are env-overridable via relaxed binding, e.g. RAGGEDLIB_LIBRARY_PATH, RAGGEDLIB_INGEST_EXTRACT_CONCURRENCY. */
@ConfigurationProperties("raggedlib")
data class RaggedlibProperties(
    /**
     * Legacy single-library path (deprecated): used as the one configured library only while [libraries] is empty,
     * and as the match target for the one-time backfill of books indexed before multi-library support (see
     * LibraryRegistry) even once [libraries] is in use.
     */
    val libraryPath: Path? = null,
    /** The Calibre libraries to index and search, in order. Overrides [libraryPath] when non-empty. */
    val libraries: List<LibraryConfig> = emptyList(),
    /** Where ebook-convert / pdftotext write their temporary output. */
    val tmpDir: Path,
    val metadataSource: MetadataSource = MetadataSource.AUTO,
    val corsOrigins: List<String> = listOf("http://localhost:3000"),
    val tools: Tools = Tools(),
    val ingest: Ingest = Ingest(),
    val chunking: Chunking = Chunking(),
    val search: Search = Search(),
    val chat: Chat = Chat(),
    val embedding: Embedding = Embedding(),
    val rerank: Rerank = Rerank(),
    val mcp: Mcp = Mcp(),
    val ocr: Ocr = Ocr(),
    val auth: Auth = Auth(),
) {
    /** One configured Calibre library. [name] is just a display label; blank defaults to the path's last segment. */
    data class LibraryConfig(val name: String = "", val path: Path = Path.of(""))

    /** [libraries] if configured, else one library from the legacy [libraryPath]; never empty once validated. */
    fun effectiveLibraries(): List<LibraryConfig> = when {
        libraries.isNotEmpty() -> libraries.map { if (it.name.isNotBlank()) it else it.copy(name = it.path.fileName?.toString() ?: it.path.toString()) }
        libraryPath != null -> listOf(LibraryConfig("default", libraryPath))
        else -> error("configure raggedlib.libraries (one or more {name, path}) or the legacy raggedlib.library-path")
    }

    data class Tools(
        val calibredb: String = "calibredb",
        val ebookConvert: String = "ebook-convert",
        val pdftotext: String = "pdftotext",
        val pdftoppm: String = "pdftoppm",
        val pdfinfo: String = "pdfinfo",
        val pdfimages: String = "pdfimages",
        val tesseract: String = "tesseract",
        val calibredbTimeout: Duration = Duration.ofMinutes(2),
        val convertTimeout: Duration = Duration.ofMinutes(10),
    )

    data class Ingest(
        /**
         * Reset books left mid-pipeline (EXTRACTING/EMBEDDING) to PENDING at startup. Disable only for an extra,
         * read-only instance sharing the database with one that is indexing — it would wipe that run's work.
         */
        val recoverOnStartup: Boolean = true,
        /**
         * Parallel extraction workers (external processes, CPU bound): the default for "books indexed in parallel",
         * which can be changed at runtime (Settings; stored in app_settings).
         */
        val extractConcurrency: Int = 3,
        /** Upper bound for that setting; 0 = the number of cores, at most 16. */
        val maxExtractConcurrency: Int = 0,
        /** Extracted books waiting for the embedder (bounds memory). */
        val queueCapacity: Int = 3,
        /** Texts per Ollama /api/embed call. */
        val embedBatchSize: Int = 32,
        /** Concurrent embed calls (match OLLAMA_NUM_PARALLEL). */
        val embedParallelism: Int = 2,
        /** PDFs with fewer letters per page on average are treated as having no text layer. */
        val minLettersPerPage: Int = 80,
        /**
         * A book whose passages are, for at least this share, word for word in one other indexed book that is at
         * least as long becomes DUPLICATE of it instead of being embedded (see Duplicates). 0 turns this off.
         */
        val duplicateMinShare: Double = 0.9,
    )

    /**
     * OCR of PDF pages that have no text layer (see PdfOcr): scanned books, and scanned pages bound into a
     * born-digital PDF. Needs Tesseract with the traineddata of [languages] (the Docker image installs them).
     */
    data class Ocr(
        val enabled: Boolean = true,
        /**
         * Tesseract languages, `+`-separated (`eng+nor`), used for every book in addition to its Calibre languages.
         * Each one is a traineddata file that must be installed; uninstalled ones are left out.
         */
        val languages: String = "eng",
        /** Pages OCR'd at once, across all books; each runs one single-threaded Tesseract. */
        val concurrency: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8),
        /** Resolution pages are rendered at; Tesseract is tuned for about 300. */
        val dpi: Int = 300,
        /** Per page, for rendering and for recognition each. */
        val pageTimeout: Duration = Duration.ofMinutes(3),
        /**
         * A page whose mean word confidence is lower keeps its (lack of) text: what Tesseract reads off photos and
         * diagrams scores far lower than text, even poorly scanned text.
         */
        val minConfidence: Double = 50.0,
        /**
         * In a PDF with a text layer, a page without text is OCR'd only if raster images cover this share of it:
         * a scanned page, not a blank one or a figure.
         */
        val minImageCoverage: Double = 0.5,
    )

    data class Chunking(
        val targetChars: Int = 1500,
        val maxChars: Int = 2000,
        val overlapChars: Int = 200,
        val minChars: Int = 80,
    )

    data class Search(
        val defaultTopK: Int = 8,
        val maxTopK: Int = 50,
        val vectorCandidates: Int = 40,
        val textCandidates: Int = 40,
        val rrfK: Int = 60,
        val maxChunksPerBook: Int = 3,
        val hnswEfSearch: Int = 100,
        /**
         * Vector hits are weighted in RRF by cosine similarity: 0 at/below the floor, 1 at/above "full".
         * Calibrated for qwen3-embedding:0.6b with the query instruction (relevant ≈ 0.6–0.8, noise ≈ 0.4–0.5);
         * re-check after changing the embedding model. Set both to 0 for plain, unweighted RRF.
         */
        val vectorSimFloor: Double = 0.40,
        val vectorSimFull: Double = 0.70,
        /** Fused candidates scoring below this fraction of the best one are dropped (before the per-book cap). */
        val minRelativeScore: Double = 0.10,
        /**
         * A candidate sharing at least this share of its word 5-grams with a better one is the same passage (repeated
         * in a book, shared by volumes or editions) and dropped. 0 keeps them all.
         */
        val nearDuplicateContainment: Double = 0.6,
        /** Full-text search only looks at this many leading characters of the query. */
        val maxKeywordQueryChars: Int = 500,
        /** Keyword fallback (see KeywordFallback): min IDF-weighted share of query terms a passage must contain. */
        val fallbackMinCoverage: Double = 0.5,
        /** Terms in more than this fraction of chunks are "common": they score, but can't nominate candidates. */
        val fallbackCommonTermFrequency: Double = 0.05,
        /** At most this many (rarest) query terms take part in the fallback. */
        val fallbackMaxTerms: Int = 12,
        /** How long lexeme document frequencies are cached; the corpus grows while indexing. */
        val termStatsTtlSeconds: Long = 600,
        val spelling: Spelling = Spelling(),
    )

    /**
     * Spelling correction of search queries (see SpellingCorrector): words the library doesn't contain are replaced
     * by the closest frequent word it does, and the response says so (`correctedQuery`).
     */
    data class Spelling(
        val enabled: Boolean = true,
        /** Shorter words are never corrected: one edit turns most of them into another word ("Hono" -> mono). */
        val minWordLength: Int = 5,
        /** An unknown word this long may be corrected two edits away instead of one (shorter: "comptime" -> comptia). */
        val longWordLength: Int = 9,
        /** Words in more passages (as typed) are never corrected. */
        val typoMaxDocs: Int = 50,
        /** A correction must occur in at least this many passages. */
        val minCandidateDocs: Int = 20,
        /**
         * A word the library contains is only corrected to one this many times as frequent ("recieve", in 24
         * passages, to "receive", in 11000; "minced" stays, though "minded" is ~40x as common).
         */
        val minFrequencyRatio: Double = 200.0,
        /** Words in fewer passages are left out of the vocabulary (mostly typos and extraction noise). */
        val minWordDocs: Int = 3,
    )

    data class Chat(
        val numCtx: Int = 16384,
        val temperature: Double = 0.3,
        val historyMessages: Int = 6,
        val disableThinking: Boolean = true,
        val rewriteNumPredict: Int = 96,
        /**
         * Excerpt text given to the model per answer; passages beyond it are left out (and not returned as sources).
         * Ollama splits num-ctx across OLLAMA_NUM_PARALLEL slots (16384 / 2 = 8192 tokens per request) and silently
         * drops the *start* of a longer prompt — the system prompt and the best passages. 16000 chars ≈ 4.5K tokens,
         * leaving room for instructions, history and the answer.
         */
        val maxContextChars: Int = 16_000,
    )

    data class Embedding(
        val dimensions: Int = 1024,
        /**
         * The model that embedded passages stored before the model was recorded with each one. Re-indexing reuses a
         * stored embedding only if it came from the current model.
         */
        val legacyModel: String = "qwen3-embedding:0.6b",
        /** Prefix for search queries (qwen3-embedding is instruction-aware); empty to disable. */
        val queryInstruction: String = "Instruct: Given a question, retrieve passages from technical books that answer it\nQuery: ",
    )

    /**
     * Cross-encoder reranking of the fused candidates: a model that reads query and passage together
     * (bge-reranker-v2-m3 behind llama.cpp's `/v1/rerank`). Besides a better order it gives an absolute relevance
     * score, so passages nothing in the library really matches can be dropped. If the reranker is down or slow,
     * search falls back to the fused order.
     */
    data class Rerank(
        /** Off: never call the reranker (requests asking for it get the fused order). */
        val enabled: Boolean = true,
        val baseUrl: String = "http://localhost:8091",
        /** Best fused candidates sent to the reranker (at least topK). Latency grows linearly: ~1 s for 30. */
        val candidates: Int = 30,
        /** Reranked passages scoring below this (0..1) are dropped, so a query with no real match comes back empty. */
        val minScore: Double = 0.10,
        /** llama.cpp returns bge-reranker's raw logit; map it to 0..1. Off for servers that return probabilities. */
        val sigmoid: Boolean = true,
        /** Query and passage text sent to the reranker are cut to these lengths. */
        val maxQueryChars: Int = 1000,
        val maxDocumentChars: Int = 2000,
        val connectTimeout: Duration = Duration.ofSeconds(1),
        /** A 30-passage request takes ~1 s on the GPU, ~3 s while a chat answer is generating. */
        val timeout: Duration = Duration.ofSeconds(5),
        /** In-flight requests (match llama.cpp's -np); a search that can't get a slot within [queueWait] isn't reranked. */
        val maxConcurrent: Int = 4,
        val queueWait: Duration = Duration.ofSeconds(2),
        /** After a failure, searches skip the reranker this long instead of each waiting out the timeout. */
        val cooldown: Duration = Duration.ofSeconds(15),
    )

    /**
     * MCP endpoint (`/mcp`, streamable HTTP) for agent harnesses. Every request needs an API key, sent as
     * `Authorization: Bearer <key>` or `X-API-Key: <key>`: one a user minted (Account → API keys), a system-wide one
     * an admin created, or one of [apiKeys] from the configuration.
     */
    data class Mcp(
        /** System-wide keys from the configuration, with the `library:read` scope on every library. */
        val apiKeys: List<ApiKey> = emptyList(),
        /**
         * Upper bound for one `ask_library` call (retrieval + generation); the answer is cut off there and returned
         * as far as it got. The stateless transport can't notice a client that gave up, so this also bounds the GPU
         * time an abandoned call can take. Clients should allow somewhat more than this.
         */
        val answerTimeout: Duration = Duration.ofSeconds(150),
    )

    /**
     * [name] identifies the client in logs. [key] is the secret itself, or `sha256:<hex>` of it so the plain key
     * needn't sit in the configuration. Entries with a blank key are ignored.
     */
    data class ApiKey(val name: String = "", val key: String = "")

    /** Sign-in and roles. */
    data class Auth(
        /**
         * `oidc`: users sign in through an OpenID Connect provider (configured by the `oidc` profile, see
         * `application-oidc.yml`, which also sets this). `single-user`: no sign-in, every request without an API key
         * is one built-in local admin (dev.raggedlib.auth.AuthMode).
         */
        val mode: String = "single-user",
        /** Claim (ID token or userinfo) with the user's groups, for [groupRoles]; a string or a list of strings. */
        val groupsClaim: String = "groups",
        /** Claim that makes a user a global admin when it holds any of [adminValues]; blank = [groupsClaim]. */
        val adminClaim: String = "",
        /**
         * Values of [adminClaim] that make a user a global admin (any one is enough; a list claim needs to contain
         * one). Empty = no one is admin through the claim; admins can still promote users in the app.
         */
        val adminValues: List<String> = emptyList(),
        /** Role every signed-in user has in every library, at least: none, viewer or curator. */
        val defaultLibraryRole: String = "none",
        /** Library roles granted to members of IdP groups, besides the ones granted in the app. */
        val groupRoles: List<GroupRole> = emptyList(),
        /** Where the browser goes after signing in or failing to (the web UI's root, through its /api proxy). */
        val postLoginRedirect: String = "/",
    )

    /** Members of [group] have [role] in [library]: a library name or id, or `*` for every library. */
    data class GroupRole(val group: String = "", val role: String = "viewer", val library: String = "*")
}
