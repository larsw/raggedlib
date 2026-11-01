package dev.raggedlib.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

enum class MetadataSource { AUTO, CALIBREDB, SQLITE }

/** All settings are env-overridable via relaxed binding, e.g. RAGGEDLIB_LIBRARY_PATH, RAGGEDLIB_INGEST_EXTRACT_CONCURRENCY. */
@ConfigurationProperties("raggedlib")
data class RaggedlibProperties(
    val libraryPath: Path,
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
) {
    data class Tools(
        val calibredb: String = "calibredb",
        val ebookConvert: String = "ebook-convert",
        val pdftotext: String = "pdftotext",
        val calibredbTimeout: Duration = Duration.ofMinutes(2),
        val convertTimeout: Duration = Duration.ofMinutes(10),
    )

    data class Ingest(
        /**
         * Reset books left mid-pipeline (EXTRACTING/EMBEDDING) to PENDING at startup. Disable only for an extra,
         * read-only instance sharing the database with one that is indexing — it would wipe that run's work.
         */
        val recoverOnStartup: Boolean = true,
        /** Parallel extraction workers (external processes, CPU bound). */
        val extractConcurrency: Int = 3,
        /** Extracted books waiting for the embedder (bounds memory). */
        val queueCapacity: Int = 3,
        /** Texts per Ollama /api/embed call. */
        val embedBatchSize: Int = 32,
        /** Concurrent embed calls (match OLLAMA_NUM_PARALLEL). */
        val embedParallelism: Int = 2,
        /** PDFs with fewer letters per page on average are treated as having no text layer. */
        val minLettersPerPage: Int = 80,
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
    )

    data class Chat(
        val numCtx: Int = 16384,
        val temperature: Double = 0.3,
        val historyMessages: Int = 6,
        val disableThinking: Boolean = true,
        val rewriteNumPredict: Int = 96,
    )

    data class Embedding(
        val dimensions: Int = 1024,
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
}
