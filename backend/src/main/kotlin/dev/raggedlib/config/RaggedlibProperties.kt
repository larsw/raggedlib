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
) {
    data class Tools(
        val calibredb: String = "calibredb",
        val ebookConvert: String = "ebook-convert",
        val pdftotext: String = "pdftotext",
        val calibredbTimeout: Duration = Duration.ofMinutes(2),
        val convertTimeout: Duration = Duration.ofMinutes(10),
    )

    data class Ingest(
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
}
