package dev.raggedlib.cancel

import dev.raggedlib.auth.Access
import dev.raggedlib.auth.Caller
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * Cancelling a request while it is being handled. A request that carries an id ([HEADER], such as a UUID) gets a
 * [Cancellation] of its own, current while it is handled ([RequestCancellationFilter]), and
 * `POST /api/requests/{id}/cancel` from the same caller cancels it. Work that honours it (the vector space's layouts
 * and topics: their Postgres queries are stopped, their loops give up) then ends with [RequestCancelledException].
 *
 * A servlet container never notices a client going away while a request is handled, only once writing the response
 * fails, so an aborted fetch would otherwise compute its answer to the end, for no one. The web UI's server
 * (frontend/proxy.ts) gives every request an id and cancels it when the browser aborts it before the answer came.
 */
@Component
class RequestCancellations {
    internal var clock: () -> Long = System::currentTimeMillis

    private data class Key(val caller: String, val id: String)

    private val running = ConcurrentHashMap<Key, Cancellation>()

    /** Cancels that came before their request did (it may still be on its way), each until it expires. */
    private val early = ConcurrentHashMap<Key, Long>()

    /** Runs [block] with a [Cancellation] that [cancel] with [caller] and [id] cancels, as [Cancellation.current]. */
    fun <T> handle(caller: String, id: String, block: () -> T): T {
        val key = Key(caller, id)
        val cancellation = Cancellation()
        // registered first, looked for in [early] second, the other way round from [cancel]: a cancel arriving
        // meanwhile is seen by one of the two
        running[key] = cancellation
        try {
            val expires = early.remove(key)
            if (expires != null && expires > clock()) cancellation.cancel()
            return cancellation.bind(block)
        } finally {
            running.remove(key, cancellation)
        }
    }

    /**
     * Cancels [caller]'s request [id]. True if it was running; otherwise the cancel is kept for [EARLY_MS], so the
     * request is cancelled as soon as it arrives, if it is still on its way.
     */
    fun cancel(caller: String, id: String): Boolean {
        if (id.length > MAX_ID_LENGTH) return false // no request can have it (see [idOf]): nothing to keep
        val key = Key(caller, id)
        running[key]?.let {
            it.cancel()
            return true
        }
        val now = clock()
        if (early.size >= MAX_EARLY / 2) early.values.removeIf { it <= now }
        if (early.size >= MAX_EARLY) return false
        // one caller can't fill the map, and so have everyone else's early cancels dropped
        if (early.size >= MAX_EARLY_PER_CALLER && early.keys.count { it.caller == caller } >= MAX_EARLY_PER_CALLER) return false
        early[key] = now + EARLY_MS
        return running[key]?.also { early.remove(key); it.cancel() } != null
    }

    /** Requests running that can be cancelled now (for tests). */
    internal fun runningCount(): Int = running.size

    companion object {
        /**
         * The request's id, for cancelling it: never reused by the caller (the web UI's server sends a UUID), since a
         * cancel that arrives after its request ended is kept for [EARLY_MS] and would cancel the next one with it.
         */
        const val HEADER = "X-Request-Id"
        private const val MAX_ID_LENGTH = 128
        private const val EARLY_MS = 60_000L
        private const val MAX_EARLY = 10_000
        internal const val MAX_EARLY_PER_CALLER = 1_000

        /** The [HEADER] of [request] if it can be one. */
        fun idOf(request: HttpServletRequest): String? =
            request.getHeader(HEADER)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID_LENGTH }

        /**
         * Who may cancel the request: whoever made it, with the same credentials. One API key (by its id: keys can
         * share a name), or the user's browser sessions.
         */
        fun callerKey(caller: Caller): String = caller.apiKeyId?.let { "key $it" } ?: "user ${caller.userId}"
    }
}

/**
 * Makes a request with an id ([RequestCancellations.HEADER]) cancellable while it is handled. After Spring Security
 * (a filter bean comes after its filter chain), so the caller is known.
 */
@Component
class RequestCancellationFilter(
    private val cancellations: RequestCancellations,
    private val access: Access,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val id = RequestCancellations.idOf(request)
        val caller = access.callerOrNull()
        if (id == null || caller == null) return chain.doFilter(request, response)
        cancellations.handle(RequestCancellations.callerKey(caller), id) { chain.doFilter(request, response) }
    }
}

@RestController
class RequestCancellationController(private val cancellations: RequestCancellations, private val access: Access) {
    /**
     * Cancels the caller's request [id] (see [RequestCancellations]). `204` whether or not it was still running: it
     * may have finished meanwhile, or not have arrived yet.
     */
    @PostMapping("/api/requests/{id}/cancel")
    fun cancel(@PathVariable id: String): ResponseEntity<Void> {
        cancellations.cancel(RequestCancellations.callerKey(access.caller()), id)
        return ResponseEntity.noContent().build()
    }
}
