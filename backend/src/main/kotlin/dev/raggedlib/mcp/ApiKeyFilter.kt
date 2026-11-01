package dev.raggedlib.mcp

import dev.raggedlib.config.RaggedlibProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * API-key check in front of the MCP endpoint. Keys come from `raggedlib.mcp.api-keys`; a request must carry one as
 * `Authorization: Bearer <key>` or `X-API-Key: <key>`. Keys are compared as SHA-256 digests in constant time, and
 * every configured key is checked, so timing reveals neither which key nor how much of one matched.
 * The matched key's name is put in the logging MDC as `mcpClient` for the duration of the request.
 */
class ApiKeyFilter(keys: List<RaggedlibProperties.ApiKey>) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Key(val name: String, val digest: ByteArray)

    private val keys: List<Key> = keys.mapIndexedNotNull { i, k ->
        val value = k.key.trim()
        if (value.isEmpty()) return@mapIndexedNotNull null
        val name = k.name.ifBlank { "key-${i + 1}" }
        val hashed = HASHED.matchEntire(value)
        val digest = when {
            hashed != null -> {
                val hex = hashed.groupValues[1]
                require(hex.matches(Regex("[0-9a-fA-F]{64}"))) { "raggedlib.mcp.api-keys[$i] ($name): sha256: needs 64 hex characters" }
                HexFormat.of().parseHex(hex)
            }
            // e.g. "sha-512:…" or a mangled prefix: taken as a plain key, the visible hash would be the password
            LOOKS_HASHED.matches(value) ->
                throw IllegalArgumentException("raggedlib.mcp.api-keys[$i] ($name) looks like a hash but isn't 'sha256:<64 hex>'")
            else -> sha256(value)
        }
        Key(name, digest)
    }

    init {
        if (this.keys.isEmpty()) {
            log.warn("MCP endpoint has no API keys configured (raggedlib.mcp.api-keys) — every MCP request will be refused")
        } else {
            log.info("MCP endpoint accepts {} API key(s): {}", this.keys.size, this.keys.joinToString { it.name })
        }
    }

    /** Name of the key matching [presented], or null. */
    internal fun match(presented: String?): String? {
        if (presented.isNullOrEmpty()) return null
        val digest = sha256(presented)
        var found: String? = null
        for (k in keys) {
            if (MessageDigest.isEqual(digest, k.digest) && found == null) found = k.name
        }
        return found
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        // CORS preflight carries no credentials; there is no CORS mapping for /mcp, so it gets no grant either
        if (request.method == "OPTIONS") return chain.doFilter(request, response)
        val name = match(presented(request))
        if (name == null) {
            val reason = if (keys.isEmpty()) "no API keys are configured on the server" else "missing or invalid API key"
            log.debug("MCP request refused from {}: {}", request.remoteAddr, reason)
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"raggedlib-mcp\"")
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            response.writer.write("""{"error":"unauthorized","message":"$reason; send Authorization: Bearer <key> or X-API-Key: <key>"}""")
            return
        }
        MDC.put(MDC_KEY, name)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    private fun presented(request: HttpServletRequest): String? {
        request.getHeader("X-API-Key")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val auth = request.getHeader(HttpHeaders.AUTHORIZATION)?.trim() ?: return null
        return if (auth.length > 7 && auth.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) auth.substring(7).trim() else null
    }

    companion object {
        const val MDC_KEY = "mcpClient"
        private val HASHED = Regex("(?i)sha-?256:\\s*(.*)")
        private val LOOKS_HASHED = Regex("(?i)[a-z0-9-]{2,12}:\\s*[0-9a-f]{32,}")

        fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8))
    }
}
