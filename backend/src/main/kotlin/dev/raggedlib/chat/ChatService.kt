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

data class HistoryMessage(val role: String = "user", val content: String = "")

data class ChatRequest(
    val query: String = "",
    val topK: Int? = null,
    val bookIds: List<Long>? = null,
    val tags: List<String>? = null,
    val history: List<HistoryMessage>? = null,
    val model: String? = null,
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

    fun retrieve(req: ChatRequest, query: String): List<SourceChunk> =
        search.search(SearchRequest(query, req.topK, req.bookIds, req.tags))

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

        internal fun cleanRewrite(s: String?): String? {
            if (s == null) return null
            val t = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()
                .lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?.removePrefix("Standalone search query:")?.trim()?.trim('"', '\'', '`')?.trim()
            return t?.takeIf { it.length in 2..500 }
        }
    }
}
