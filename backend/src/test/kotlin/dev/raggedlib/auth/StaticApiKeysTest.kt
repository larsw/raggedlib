package dev.raggedlib.auth

import dev.raggedlib.config.RaggedlibProperties.ApiKey
import org.springframework.mock.web.MockHttpServletRequest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StaticApiKeysTest {
    private val keys = StaticApiKeys(listOf(
        ApiKey("claude", "s3cret-one"),
        ApiKey("ci", "sha256:" + HexFormat.of().formatHex(StaticApiKeys.sha256("s3cret-two"))),
        ApiKey("blank", "  "),
    ))

    @Test
    fun `matches a plain or hashed configured key`() {
        assertEquals("claude", keys.match("s3cret-one"))
        assertEquals("ci", keys.match("s3cret-two"))
        assertEquals(2, keys.size)
    }

    @Test
    fun `refuses missing, wrong and blank-configured keys`() {
        assertNull(keys.match(null))
        assertNull(keys.match(""))
        assertNull(keys.match("  "))
        assertNull(keys.match("s3cret-on"))
        assertNull(keys.match("sha256:" + HexFormat.of().formatHex(StaticApiKeys.sha256("s3cret-two")))) // the hash isn't the key
    }

    @Test
    fun `with no keys configured nothing matches`() {
        assertNull(StaticApiKeys(emptyList()).match("anything"))
    }

    @Test
    fun `malformed or oddly prefixed hashed keys fail at startup instead of becoming plain keys`() {
        val hex = HexFormat.of().formatHex(StaticApiKeys.sha256("k"))
        for (bad in listOf("sha256:abc", "sha256:${hex}zz", "sha512:$hex$hex", "md5:0123456789abcdef0123456789abcdef")) {
            assertFailsWith<IllegalArgumentException>(bad) { StaticApiKeys(listOf(ApiKey("x", bad))) }
        }
        // prefix case, a dash and surrounding whitespace are fine: still the hash of "k", not a plain key
        for (ok in listOf("SHA256:$hex", " sha-256:$hex ", "Sha256: ${hex.uppercase()}")) {
            val k = StaticApiKeys(listOf(ApiKey("x", ok)))
            assertEquals("x", k.match("k"), ok)
            assertNull(k.match(ok.trim()), ok)
        }
    }

    @Test
    fun `a key is read from a bearer token or X-API-Key, nothing else`() {
        fun presented(vararg headers: Pair<String, String>) =
            ApiKeyAuthenticationConverter.presentedKey(MockHttpServletRequest("POST", "/mcp").apply { headers.forEach { (k, v) -> addHeader(k, v) } })

        assertEquals("abc", presented("Authorization" to "Bearer abc"))
        assertEquals("abc", presented("authorization" to "bearer  abc "))
        assertEquals("abc", presented("X-API-Key" to "abc"))
        assertEquals("xk", presented("X-API-Key" to "xk", "Authorization" to "Bearer other"))
        assertNull(presented())
        assertNull(presented("Authorization" to "Basic czNjcmV0LW9uZQ=="))
        assertNull(presented("Authorization" to "abc"))
        assertNull(presented("Authorization" to "Bearer "))
    }
}
