package dev.raggedlib.chat

import dev.raggedlib.config.badRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSE over POST. Events: `sources` (once) -> `token`* -> `done` | `error`.
 *
 * Uses ResponseBodyEmitter (not StreamingResponseBody): in Spring 7 the stream handed to StreamingResponseBody
 * is non-flushing, whereas every ResponseBodyEmitter.send() is flushed to the client immediately.
 */
@RestController
@RequestMapping("/api")
class ChatController(
    private val chat: ChatService,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val utf8Text = MediaType("text", "plain", StandardCharsets.UTF_8)

    @PostMapping("/chat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chat(@RequestBody req: ChatRequest): ResponseEntity<ResponseBodyEmitter> {
        if (req.query.isBlank()) badRequest("query must not be empty")
        val emitter = ResponseBodyEmitter(30 * 60 * 1000L)
        val closed = AtomicBoolean(false)
        emitter.onTimeout { closed.set(true) }
        emitter.onError { closed.set(true) }
        emitter.onCompletion { closed.set(true) }
        Thread.ofVirtual().name("chat-", 0).start { run(req, emitter, closed) }
        return ResponseEntity.ok()
            .contentType(MediaType("text", "event-stream", StandardCharsets.UTF_8))
            .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
            .header("X-Accel-Buffering", "no")
            .body(emitter)
    }

    private fun run(req: ChatRequest, emitter: ResponseBodyEmitter, closed: AtomicBoolean) {
        val started = System.currentTimeMillis()
        try {
            val query = chat.standaloneQuery(req)
            if (query != req.query) log.info("Rewrote follow-up '{}' -> '{}'", req.query, query)
            val found = chat.retrieve(req, query)
            val sources = found.results
            if (found.correctedQuery != null) {
                send(emitter, "corrected", mapOf("correctedQuery" to found.correctedQuery, "corrections" to found.corrections))
            }
            send(emitter, "sources", sources)
            chat.stream(req, sources).toStream(1).use { tokens ->
                for (t in tokens) {
                    if (closed.get()) break // client went away: closing the stream cancels the LLM call
                    if (t.isNotEmpty()) send(emitter, "token", mapOf("t" to t))
                }
            }
            if (!closed.get()) {
                send(emitter, "done", mapOf("elapsedMs" to System.currentTimeMillis() - started))
                emitter.complete()
            }
        } catch (e: Exception) {
            if (closed.get()) {
                log.debug("Chat client disconnected: {}", e.message)
                return
            }
            log.warn("Chat failed: {}", e.message, e)
            try {
                send(emitter, "error", mapOf("error" to (e.message ?: e.javaClass.simpleName)))
                emitter.complete()
            } catch (_: Exception) {
            }
        }
    }

    private fun send(emitter: ResponseBodyEmitter, event: String, data: Any) {
        emitter.send("event: $event\ndata: ${mapper.writeValueAsString(data)}\n\n", utf8Text)
    }
}
