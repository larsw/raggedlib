package dev.raggedlib.auth

import dev.raggedlib.config.RaggedlibProperties
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/**
 * System-wide API keys from the configuration (`raggedlib.mcp.api-keys`, docker-compose's `MCP_API_KEY`), as before
 * keys could be created in the app. Each acts as a system key with the `library:read` scope on every library.
 *
 * A key is configured as itself or as `sha256:<hex>` of it, so the plain key needn't sit in the configuration.
 * Keys are compared as SHA-256 digests in constant time, and every configured key is checked, so timing reveals
 * neither which key nor how much of one matched.
 */
class StaticApiKeys(keys: List<RaggedlibProperties.ApiKey>) {
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
        if (this.keys.isNotEmpty()) log.info("{} system API key(s) from the configuration: {}", this.keys.size, this.keys.joinToString { it.name })
    }

    val size: Int get() = keys.size

    /** Name of the configured key matching [presented], or null. */
    fun match(presented: String?): String? {
        if (presented.isNullOrEmpty()) return null
        val digest = sha256(presented)
        var found: String? = null
        for (k in keys) {
            if (MessageDigest.isEqual(digest, k.digest) && found == null) found = k.name
        }
        return found
    }

    companion object {
        private val HASHED = Regex("(?i)sha-?256:\\s*(.*)")
        private val LOOKS_HASHED = Regex("(?i)[a-z0-9-]{2,12}:\\s*[0-9a-f]{32,}")

        fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8))
    }
}
