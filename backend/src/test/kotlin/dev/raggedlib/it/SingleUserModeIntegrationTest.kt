package dev.raggedlib.it

import dev.raggedlib.RaggedlibApplication
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `raggedlib.auth.mode=single-user`: no sign-in, every request without an API key is the local admin, and API keys
 * still work. Runs with the `dex` profile active and an issuer nothing listens on: the toggle must win over a
 * configured OIDC client, without contacting its provider (startup would fail if it did). Skipped without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("dex")
@Testcontainers(disabledWithoutDocker = true)
class SingleUserModeIntegrationTest {
    companion object {
        private val appPort = ServerSocket(0).use { it.localPort }
        private val base = "http://localhost:$appPort"
        private const val STATIC_KEY = "single-user-static-key-0123"

        @Container
        @JvmField
        val postgres: PostgreSQLContainer = PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18").asCompatibleSubstituteFor("postgres"),
        )

        private fun common(r: (String, () -> Any) -> Unit) {
            r("spring.datasource.url") { postgres.jdbcUrl }
            r("spring.datasource.username") { postgres.username }
            r("spring.datasource.password") { postgres.password }
            r("raggedlib.libraries[0].name") { "Alpha" }
            r("raggedlib.libraries[0].path") { Files.createTempDirectory("rl-su-alpha").toString() }
            r("raggedlib.rerank.enabled") { false }
            r("raggedlib.ocr.enabled") { false }
            r("spring.ai.ollama.base-url") { "http://localhost:9" }
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(r: DynamicPropertyRegistry) {
            common { k, v -> r.add(k, v) }
            r.add("server.port") { appPort }
            r.add("raggedlib.auth.mode") { "single-user" }
            r.add("OIDC_ISSUER_URI") { "http://localhost:9/dex" } // nothing there: must not be discovered
            r.add("raggedlib.mcp.api-keys[0].name") { "ci" }
            r.add("raggedlib.mcp.api-keys[0].key") { STATIC_KEY }
        }
    }

    private val browser = Browser(base)

    @Test
    fun `every request without a key is the local admin, and there is no sign-in`() {
        val me = browser.get("/api/me")
        assertEquals(200, me.status, me.body)
        assertEquals("single-user", me.json.path("authMode").asString())
        assertEquals("Local user", me.json.path("displayName").asString())
        assertTrue(me.json.path("admin").asBoolean())
        assertEquals(listOf("ADMIN"), browser.get("/api/libraries").json.values().map { it.path("role").asString() })
        assertEquals(200, browser.get("/api/admin/users").status)
        assertEquals(404, browser.get("/api/auth/login/oidc").status)
    }

    @Test
    fun `writes still need the CSRF token`() {
        assertEquals(403, browser.post("/api/lists", mapOf("name" to "no token"), csrf = false).status)
        assertEquals(201, browser.post("/api/lists", mapOf("name" to "with token")).status)
    }

    @Test
    fun `API keys work as in multi-user mode`() {
        val anon = Browser(base)
        assertEquals(401, anon.mcp("tools/list", key = null).status)
        val key = browser.post("/api/me/api-keys", mapOf("name" to "agent", "scopes" to listOf("library:read", "favorites:read")))
            .json.path("rawKey").asString()
        val tools = anon.tools(key)
        assertTrue("list_favorite_lists" in tools && "search_library" in tools && "write_book_review" !in tools, "$tools")
        assertFalse(anon.callTool("list_libraries", emptyMap(), key).path("isError").asBoolean())
        assertEquals(403, anon.post("/api/lists", mapOf("name" to "x"), key = key).status)
        assertTrue("search_library" in anon.tools(STATIC_KEY))
    }

    @Test
    fun `mode oidc without an OIDC client refuses to start`() {
        // command-line arguments: they take precedence over application.yml, as an environment variable would
        val args = buildList {
            common { k, v -> add("--$k=${v()}") }
            add("--server.port=0")
            add("--raggedlib.auth.mode=oidc")
        }
        val failure = runCatching {
            SpringApplicationBuilder(RaggedlibApplication::class.java).run(*args.toTypedArray()).close()
        }.exceptionOrNull()
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { "no OIDC client is configured" in it }, "$messages")
    }
}
