package dev.raggedlib.search

import dev.raggedlib.config.RaggedlibProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.NestedExceptionUtils
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp

/**
 * Client for a cross-encoder reranker behind a Jina/Cohere-style `POST /v1/rerank` (llama.cpp `--reranking`).
 * Failures never fail a search: [score] returns null and the caller keeps the fused order. It also returns null
 * without calling when the reranker is saturated (no slot within `queue-wait`) or failed recently (`cooldown`),
 * so a hung or overloaded reranker costs searches nothing instead of a timeout each.
 */
@Component
class Reranker internal constructor(private val cfg: RaggedlibProperties.Rerank, private val client: RestClient) {

    @Autowired
    constructor(props: RaggedlibProperties, restClientBuilder: RestClient.Builder) : this(
        props.rerank,
        restClientBuilder.clone()
            .baseUrl(props.rerank.baseUrl)
            .requestFactory(SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(props.rerank.connectTimeout)
                setReadTimeout(props.rerank.timeout)
            })
            .build(),
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastWarn = AtomicLong(0)
    private val failing = AtomicBoolean(false)
    /** System.nanoTime() before which calls are skipped; 0 = no cooldown. */
    private val skipUntil = AtomicLong(0)
    private val permits = Semaphore(cfg.maxConcurrent.coerceAtLeast(1))

    val enabled: Boolean get() = cfg.enabled

    /**
     * Relevance of each document to the query, in input order, 0..1 (with [RaggedlibProperties.Rerank.sigmoid]);
     * null if the reranker is unavailable or answers with something unusable.
     */
    fun score(query: String, documents: List<String>): DoubleArray? {
        if (documents.isEmpty()) return DoubleArray(0)
        val until = skipUntil.get()
        if (until != 0L && System.nanoTime() - until < 0) return null
        if (!permits.tryAcquire(cfg.queueWait.toMillis(), TimeUnit.MILLISECONDS)) {
            warn("all ${cfg.maxConcurrent} reranker slots busy for ${cfg.queueWait.toMillis()} ms")
            return null
        }
        return try {
            val body = client.post().uri("/v1/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("query" to query, "documents" to documents, "top_n" to documents.size))
                .retrieve()
                .body(JsonNode::class.java)
            val scores = parse(body, documents.size) ?: error("unexpected /v1/rerank response")
            skipUntil.set(0)
            if (failing.compareAndSet(true, false)) log.info("Reranker at {} is answering again", cfg.baseUrl)
            if (cfg.sigmoid) DoubleArray(scores.size) { sigmoid(scores[it]) } else scores
        } catch (e: Exception) {
            failing.set(true)
            skipUntil.set((System.nanoTime() + cfg.cooldown.toNanos()).let { if (it == 0L) 1L else it })
            // a read timeout surfaces as a "can't extract response" RestClientException; log what actually happened
            warn("${NestedExceptionUtils.getMostSpecificCause(e)}; skipping it for ${cfg.cooldown.toSeconds()} s")
            null
        } finally {
            permits.release()
        }
    }

    private fun warn(reason: String) {
        val now = System.nanoTime()
        val last = lastWarn.get()
        if (last == 0L || now - last > WARN_INTERVAL_NANOS) {
            if (lastWarn.compareAndSet(last, now)) log.warn("Reranker at {}: {} — keeping the fused order", cfg.baseUrl, reason)
        }
    }

    /** Health of the reranker server, for the system status. */
    fun reachable(): Boolean = try {
        client.get().uri("/health").retrieve().toBodilessEntity().statusCode.is2xxSuccessful
    } catch (_: Exception) {
        false
    }

    internal companion object {
        private const val WARN_INTERVAL_NANOS = 60_000_000_000L

        fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

        /** `results[].{index, relevance_score}` in any order → scores by index; null unless each index appears once. */
        fun parse(body: JsonNode?, n: Int): DoubleArray? {
            val results = body?.path("results")?.takeIf { it.isArray && it.size() == n } ?: return null
            val out = DoubleArray(n) { Double.NaN }
            for (r in results.values()) {
                val i = r.path("index").takeIf { it.isIntegralNumber }?.asInt() ?: return null
                val s = r.path("relevance_score").takeIf { it.isNumber }?.asDouble() ?: return null
                if (i !in 0 until n || !out[i].isNaN() || !s.isFinite()) return null
                out[i] = s
            }
            return out
        }
    }
}
