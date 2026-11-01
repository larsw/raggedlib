package dev.raggedlib.mcp

import dev.raggedlib.config.RaggedlibProperties.ApiKey
import org.slf4j.MDC
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiKeyFilterTest {
    private val filter = ApiKeyFilter(listOf(
        ApiKey("claude", "s3cret-one"),
        ApiKey("ci", "sha256:" + HexFormat.of().formatHex(ApiKeyFilter.sha256("s3cret-two"))),
        ApiKey("blank", "  "),
    ))

    private fun call(f: ApiKeyFilter = filter, method: String = "POST", headers: Map<String, String> = emptyMap()):
        Pair<MockHttpServletResponse, MockFilterChain> {
        val req = MockHttpServletRequest(method, "/mcp")
        headers.forEach { (k, v) -> req.addHeader(k, v) }
        val res = MockHttpServletResponse()
        val chain = MockFilterChain()
        f.doFilter(req, res, chain)
        return res to chain
    }

    @Test
    fun `accepts a plain or hashed key as bearer token or X-API-Key`() {
        assertEquals("claude", filter.match("s3cret-one"))
        assertEquals("ci", filter.match("s3cret-two"))
        for (h in listOf(mapOf("Authorization" to "Bearer s3cret-one"), mapOf("authorization" to "bearer s3cret-two"),
                         mapOf("X-API-Key" to "s3cret-one"))) {
            val (res, chain) = call(headers = h)
            assertEquals(200, res.status, h.toString())
            assertNotNull(chain.request, "request passed on for $h")
        }
    }

    @Test
    fun `refuses missing, wrong and blank-configured keys with 401`() {
        assertNull(filter.match(""))
        assertNull(filter.match("  "))
        assertNull(filter.match("s3cret-on"))
        assertNull(filter.match("sha256:" + HexFormat.of().formatHex(ApiKeyFilter.sha256("s3cret-two")))) // the hash isn't the key
        for (h in listOf(emptyMap(), mapOf("Authorization" to "Bearer nope"), mapOf("Authorization" to "Basic czNjcmV0LW9uZQ=="),
                         mapOf("Authorization" to "s3cret-one"))) {
            val (res, chain) = call(headers = h)
            assertEquals(401, res.status, h.toString())
            assertNull(chain.request, "request must not reach the MCP server for $h")
            assertTrue(res.getHeader("WWW-Authenticate")!!.startsWith("Bearer"))
        }
    }

    @Test
    fun `with no keys configured everything is refused`() {
        val (res, chain) = call(ApiKeyFilter(emptyList()), headers = mapOf("Authorization" to "Bearer anything"))
        assertEquals(401, res.status)
        assertNull(chain.request)
        assertTrue(res.contentAsString.contains("no API keys are configured"))
    }

    @Test
    fun `client name is in the MDC during the request only`() {
        var seen: String? = null
        val req = MockHttpServletRequest("POST", "/mcp").apply { addHeader("X-API-Key", "s3cret-one") }
        filter.doFilter(req, MockHttpServletResponse()) { _, _ -> seen = MDC.get(ApiKeyFilter.MDC_KEY) }
        assertEquals("claude", seen)
        assertNull(MDC.get(ApiKeyFilter.MDC_KEY))
    }

    @Test
    fun `malformed or oddly prefixed hashed keys fail at startup instead of becoming plain keys`() {
        val hex = HexFormat.of().formatHex(ApiKeyFilter.sha256("k"))
        for (bad in listOf("sha256:abc", "sha256:${hex}zz", "sha512:$hex$hex", "md5:0123456789abcdef0123456789abcdef")) {
            assertFailsWith<IllegalArgumentException>(bad) { ApiKeyFilter(listOf(ApiKey("x", bad))) }
        }
        // prefix case, a dash and surrounding whitespace are fine: still the hash of "k", not a plain key
        for (ok in listOf("SHA256:$hex", " sha-256:$hex ", "Sha256: ${hex.uppercase()}")) {
            val f = ApiKeyFilter(listOf(ApiKey("x", ok)))
            assertEquals("x", f.match("k"), ok)
            assertNull(f.match(ok.trim()), ok)
        }
    }
}
