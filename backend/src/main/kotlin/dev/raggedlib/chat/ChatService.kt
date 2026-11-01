package dev.raggedlib.chat

import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.search.SearchRequest
import dev.raggedlib.search.SearchService
import dev.raggedlib.search.SourceChunk
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.ollama.api.OllamaChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

data class HistoryMessage(val role: String = "user", val content: String = "")

data class ChatRequest(
    val query: String = "",
    val topK: Int? = null,
    val bookIds: List<Long>? = null,
    val tags: List<String>? = null,
    val history: List<HistoryMessage>? = null,
    val model: String? = null,
    val rerank: Boolean? = null,
)

/** A complete (non-streamed) answer, for callers that can't consume SSE (MCP). */
data class ChatAnswer(
    val question: String,
    /** The query actually used for retrieval (a rewritten follow-up, if there was history). */
    val searchQuery: String,
    val answer: String,
    val sources: List<SourceChunk>,
    val model: String,
    val elapsedMs: Long,
)

@Service
class ChatService(
    chatClientBuilder: ChatClient.Builder,
    private val search: SearchService,
    private val props: RaggedlibProperties,
    @Value("\${spring.ai.ollama.chat.model}") private val defaultModel: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val chatClient = chatClientBuilder.build()

    /** Rewrites a follow-up question into a standalone search query using the conversation history. */
    fun standaloneQuery(req: ChatRequest): String {
        val history = recentHistory(req)
        if (history.isEmpty()) return req.query
        val transcript = history.joinToString("\n") { "${it.role.lowercase()}: ${it.content.take(1500)}" }
        return try {
            val rewritten = chatClient.prompt()
                .system(REWRITE_PROMPT)
                .user("Conversation:\n$transcript\n\nFollow-up question: ${req.query}\n\nStandalone search query:")
                .options(options(req.model).numPredict(props.chat.rewriteNumPredict).temperature(0.0))
                .call()
                .content()
            cleanRewrite(rewritten) ?: req.query
        } catch (e: Exception) {
            log.warn("Query rewrite failed, using raw question: {}", e.message)
            req.query
        }
    }

    /** Best passages for the question, cut to the model's excerpt budget ([RaggedlibProperties.Chat.maxContextChars]). */
    fun retrieve(req: ChatRequest, query: String): List<SourceChunk> =
        withinBudget(search.search(SearchRequest(query, req.topK, req.bookIds, req.tags, req.rerank)), props.chat.maxContextChars)

    fun stream(req: ChatRequest, sources: List<SourceChunk>): Flux<String> {
        val messages = mutableListOf<Message>()
        recentHistory(req).forEach {
            messages += if (it.role.equals("assistant", true)) AssistantMessage(it.content) else UserMessage(it.content)
        }
        val user = buildString {
            append("Excerpts:\n\n")
            if (sources.isEmpty()) append("(no excerpts found)\n\n")
            sources.forEach { s ->
                append('[').append(s.ref).append("] ").append(s.title)
                if (s.authors.isNotEmpty()) append(" — ").append(s.authors.joinToString(", "))
                if (s.page != null) append(", p. ").append(s.page)
                if (s.section != null) append(", section \"").append(s.section).append('"')
                append('\n').append(s.text.trim()).append("\n\n")
            }
            append("Question: ").append(req.query)
        }
        return chatClient.prompt()
            .system(SYSTEM_PROMPT)
            .messages(messages)
            .user(user)
            .options(options(req.model).temperature(props.chat.temperature))
            .stream()
            .content()
    }

    /**
     * Retrieval + the whole answer, blocking. [timeout] covers both; when it runs out, generation is stopped (which
     * cancels the Ollama request) and the answer is returned as far as it got, marked as cut off.
     */
    fun answer(req: ChatRequest, timeout: Duration): ChatAnswer {
        val started = System.nanoTime()
        val query = standaloneQuery(req)
        val sources = retrieve(req, query)
        val left = timeout.minusNanos(System.nanoTime() - started).coerceAtLeast(Duration.ofSeconds(1))
        val cut = AtomicBoolean(false)
        val text = stream(req, sources)
            .doOnCancel { cut.set(true) } // only take(left) cancels upstream: time ran out
            .take(left)
            .collectList()
            .block()
            ?.joinToString("")
            ?.trim()
            .orEmpty()
        val answer = if (cut.get()) "$text\n\n[answer cut off after ${timeout.toSeconds()} s]".trim() else text
        return ChatAnswer(req.query, query, answer, sources, modelFor(req), (System.nanoTime() - started) / 1_000_000)
    }

    fun modelFor(req: ChatRequest): String = req.model?.takeIf { it.isNotBlank() } ?: defaultModel

    private fun options(model: String?): OllamaChatOptions.Builder {
        val b = OllamaChatOptions.builder()
            .model(model?.takeIf { it.isNotBlank() } ?: defaultModel)
            .numCtx(props.chat.numCtx)
        if (props.chat.disableThinking) b.disableThinking()
        return b
    }

    private fun recentHistory(req: ChatRequest): List<HistoryMessage> =
        (req.history ?: emptyList()).filter { it.content.isNotBlank() }.takeLast(props.chat.historyMessages)

    companion object {
        val SYSTEM_PROMPT = """
            You are a research assistant answering questions about the user's personal ebook library.
            You are given numbered excerpts from the books. Rules:
            - Answer ONLY from the numbered excerpts. Do not use outside knowledge to add facts.
            - Cite the excerpts that support each claim with their bracketed number placed right after the claim, e.g. "... [1]" or "... [2][4]". Only cite numbers that exist.
            - If the excerpts do not contain the answer, say so plainly (you may say what related information they do contain).
            - Answer in the same language as the question.
            - Use Markdown (short paragraphs, lists, code blocks where useful). Be concise and precise.
        """.trimIndent()

        val REWRITE_PROMPT = """
            Rewrite the user's follow-up question into a single standalone search query that can be understood
            without the conversation. Resolve pronouns and references using the conversation. Keep the language
            of the question. Output only the query text, no quotes, no explanation.
        """.trimIndent()

        /** Leading passages (always at least one) whose text plus per-excerpt header fits [budget] characters. */
        internal fun withinBudget(sources: List<SourceChunk>, budget: Int): List<SourceChunk> {
            var used = 0
            var n = 0
            for (s in sources) {
                used += s.text.length + EXCERPT_OVERHEAD_CHARS
                if (n > 0 && used > budget) break // a prefix: a later, shorter passage doesn't jump the queue
                n++
            }
            return sources.take(n)
        }

        private const val EXCERPT_OVERHEAD_CHARS = 120

        internal fun cleanRewrite(s: String?): String? {
            if (s == null) return null
            val t = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()
                .lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?.removePrefix("Standalone search query:")?.trim()?.trim('"', '\'', '`')?.trim()
            return t?.takeIf { it.length in 2..500 }
        }
    }
}
