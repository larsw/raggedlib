package dev.raggedlib.it

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Multi-user support end to end: real sign-in through dex (the same users as the docker-compose stack, from
 * infra/dex/config.yaml), sessions with CSRF, roles from groups and grants, favorite lists, reviews, and personal
 * and system-wide API keys on the REST API and the MCP endpoint. Postgres (pgvector) and dex run in containers;
 * Ollama and the reranker aren't needed, so nothing here searches or asks. Skipped without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("dex")
@Testcontainers(disabledWithoutDocker = true)
class AuthIntegrationTest {
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate

    companion object {
        private val appPort = freePort()
        private val dexPort = freePort()
        private val base = "http://localhost:$appPort"
        private val libraryRoots: List<Path> = listOf(Files.createTempDirectory("rl-alpha"), Files.createTempDirectory("rl-beta"))
        private const val STATIC_KEY = "static-ci-key-0123456789"

        @Container
        @JvmField
        val postgres: PostgreSQLContainer = PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18").asCompatibleSubstituteFor("postgres"),
        )

        /** The development dex, with the issuer and a redirect URI for this test's ports. */
        @Container
        @JvmField
        val dex: GenericContainer<*> = GenericContainer(DockerImageName.parse("ghcr.io/dexidp/dex:v2.45.1"))
            .withCopyToContainer(Transferable.of(dexConfig()), "/etc/dex/config.yaml")
            .withCommand("dex", "serve", "/etc/dex/config.yaml")
            .withExposedPorts(5556)
            .waitingFor(Wait.forHttp("/dex/.well-known/openid-configuration").forPort(5556))
            .also { it.portBindings = listOf("$dexPort:5556") }

        private fun dexConfig(): String {
            val config = Path.of("../infra/dex/config.yaml").toFile().readText()
            check("issuer: http://dex.localhost:5556/dex" in config && "    redirectURIs:\n" in config) { "infra/dex/config.yaml changed shape" }
            return config
                .replace("issuer: http://dex.localhost:5556/dex", "issuer: http://localhost:$dexPort/dex")
                .replace("    redirectURIs:\n", "    redirectURIs:\n      - $base/api/auth/callback/oidc\n")
        }

        private fun freePort() = ServerSocket(0).use { it.localPort }

        @DynamicPropertySource
        @JvmStatic
        fun properties(r: DynamicPropertyRegistry) {
            r.add("server.port") { appPort }
            r.add("spring.datasource.url", postgres::getJdbcUrl)
            r.add("spring.datasource.username", postgres::getUsername)
            r.add("spring.datasource.password", postgres::getPassword)
            r.add("OIDC_ISSUER_URI") { "http://localhost:$dexPort/dex" }
            r.add("raggedlib.libraries[0].name") { "Alpha" }
            r.add("raggedlib.libraries[0].path") { libraryRoots[0].toString() }
            r.add("raggedlib.libraries[1].name") { "Beta" }
            r.add("raggedlib.libraries[1].path") { libraryRoots[1].toString() }
            r.add("raggedlib.mcp.api-keys[0].name") { "ci" }
            r.add("raggedlib.mcp.api-keys[0].key") { STATIC_KEY }
            r.add("raggedlib.rerank.enabled") { false }
            r.add("raggedlib.ocr.enabled") { false }
            r.add("spring.ai.ollama.base-url") { "http://localhost:9" }
        }

        // signed-in browsers, shared by the tests (one Spring context, one dex)
        private val sessions = HashMap<String, Browser>()
        private var alphaBook = 0L
        private var betaBook = 0L
        private var alpha = 0L
        private var beta = 0L
    }

    /** Signed in as the dev dex user [name] (password = name): `<name>@raggedlib.dev`, or the plain-name g-users. */
    private fun as_(name: String): Browser = sessions.getOrPut(name) {
        Browser(base).login(if (name in PLAIN_LOGINS) name else "$name@raggedlib.dev", name)
    }

    private val PLAIN_LOGINS = setOf("gadmin", "gcurator")

    @BeforeEach
    fun seed() {
        if (alphaBook != 0L) return
        val libs = as_("admin").get("/api/libraries").json.values().associate { it.path("name").asString() to it.path("id").asLong() }
        alpha = libs.getValue("Alpha")
        beta = libs.getValue("Beta")
        fun book(lib: Long, calibreId: Long, title: String) = jdbc.queryForObject(
            "insert into books (library_id, calibre_id, title, authors, status) values (:lib, :cid, :title, '{Ann Author}', 'INDEXED') returning id",
            mapOf("lib" to lib, "cid" to calibreId, "title" to title), Long::class.java,
        )!!
        alphaBook = book(alpha, 1, "Alpha Book")
        betaBook = book(beta, 1, "Beta Book")
    }

    private fun meOf(name: String) = as_(name).get("/api/me").also { assertEquals(200, it.status, it.body) }.json

    private fun rolesOf(name: String): Map<String, String> =
        meOf(name).path("libraries").values().associate { it.path("name").asString() to it.path("role").asString() }

    @Test
    fun `unauthenticated requests are refused, with where to sign in`() {
        val anon = Browser(base)
        val me = anon.get("/api/me")
        assertEquals(401, me.status)
        assertEquals("/api/auth/login/oidc", me.json.path("loginUrl").asString())
        assertEquals(401, anon.get("/api/books").status)
        val mcp = anon.mcp("tools/list", key = null)
        assertEquals(401, mcp.status)
        assertTrue(mcp.header("WWW-Authenticate")!!.startsWith("Bearer"))
        assertEquals(401, anon.mcp("tools/list", key = "rlk_not-a-key").status)
        assertEquals(200, anon.get("/actuator/health").status)
    }

    @Test
    fun `signing in through dex gives roles from the groups claim`() {
        val admin = meOf("admin")
        assertTrue(admin.path("admin").asBoolean())
        assertEquals("oidc", admin.path("authMode").asString())
        assertEquals(mapOf("Alpha" to "ADMIN", "Beta" to "ADMIN"), rolesOf("admin"))
        assertEquals(mapOf("Alpha" to "ADMIN", "Beta" to "ADMIN"), rolesOf("librarian"))
        assertFalse(meOf("librarian").path("admin").asBoolean())
        assertEquals(mapOf("Alpha" to "CURATOR", "Beta" to "CURATOR"), rolesOf("curator"))
        assertEquals(mapOf("Alpha" to "VIEWER", "Beta" to "VIEWER"), rolesOf("reader"))
        // the plain-name users: gadmin/gadmin a global admin, gcurator/gcurator a curator of every library
        assertTrue(meOf("gadmin").path("admin").asBoolean())
        assertEquals(mapOf("Alpha" to "ADMIN", "Beta" to "ADMIN"), rolesOf("gadmin"))
        assertFalse(meOf("gcurator").path("admin").asBoolean())
        assertEquals(mapOf("Alpha" to "CURATOR", "Beta" to "CURATOR"), rolesOf("gcurator"))
        assertTrue(meOf("curator").path("canCurate").asBoolean())
        assertFalse(meOf("reader").path("canCurate").asBoolean())
        // only admins see where libraries are on disk
        assertTrue(as_("admin").get("/api/libraries").json.values().all { it.path("path").isString })
        assertTrue(as_("reader").get("/api/libraries").json.values().all { it.path("path").isNull })
    }

    @Test
    fun `a user without roles sees nothing until a library admin grants one library`() {
        val user = as_("user")
        val userId = meOf("user").path("id").asLong()
        jdbc.update("delete from library_members where user_id = :id", mapOf("id" to userId))
        Thread.sleep(5_100) // the role cache

        assertEquals(0, user.get("/api/libraries").json.size())
        assertEquals(0L, user.get("/api/books").json.path("total").asLong())
        assertEquals(404, user.get("/api/books/$alphaBook").status)
        assertEquals(404, user.get("/api/books/$alphaBook/cover").status)
        // not a library admin (it can't even see the library): may not grant itself anything
        assertEquals(404, user.put("/api/libraries/$alpha/members/$userId", mapOf("role" to "viewer")).status)
        assertEquals(403, as_("reader").put("/api/libraries/$alpha/members/$userId", mapOf("role" to "viewer")).status)

        val grant = as_("librarian").put("/api/libraries/$alpha/members/$userId", mapOf("role" to "viewer"))
        assertEquals(200, grant.status, grant.body)
        assertEquals("VIEWER", grant.json.path("role").asString())

        assertEquals(mapOf("Alpha" to "VIEWER"), rolesOf("user"))
        assertEquals(200, user.get("/api/books/$alphaBook").status)
        assertEquals(404, user.get("/api/books/$betaBook").status)
        assertEquals(listOf("Alpha Book"), user.get("/api/books").json.path("items").values().map { it.path("title").asString() })
        // a viewer can't curate
        assertEquals(403, user.post("/api/books/$alphaBook/reindex").status)
    }

    @Test
    fun `writes with a session need the CSRF token`() {
        val reader = as_("reader")
        assertEquals(403, reader.post("/api/lists", mapOf("name" to "no token"), csrf = false).status)
        assertEquals(201, reader.post("/api/lists", mapOf("name" to "with token")).status)
    }

    @Test
    fun `favorite lists are private to their owner unless shared, and only the owner changes them`() {
        val reader = as_("reader")
        val private = reader.post("/api/lists", mapOf("name" to "Private picks")).json.path("id").asLong()
        val shared = reader.post("/api/lists", mapOf("name" to "Shared picks", "shared" to true)).json.path("id").asLong()
        assertEquals(200, reader.put("/api/lists/$shared/books/$alphaBook", mapOf("note" to "great")).status)
        assertEquals(200, reader.put("/api/lists/$shared/books/$betaBook").status)

        val mine = reader.get("/api/lists?bookId=$alphaBook").json
        assertTrue(mine.path("mine").values().any { it.path("id").asLong() == shared && it.path("containsBook").asBoolean() })

        val curator = as_("curator")
        val seen = curator.get("/api/lists").json.path("shared").values().map { it.path("id").asLong() }
        assertTrue(shared in seen && private !in seen)
        assertEquals(404, curator.get("/api/lists/$private").status)
        val details = curator.get("/api/lists/$shared").json
        assertEquals("great", details.path("books").values().first { it.path("book").path("id").asLong() == alphaBook }.path("note").asString())
        assertEquals(403, curator.put("/api/lists/$shared", mapOf("name" to "Mine now")).status)
        assertEquals(403, curator.delete("/api/lists/$shared").status)

        // a reader only sees the books of libraries it can see
        val userId = meOf("user").path("id").asLong()
        jdbc.update("delete from library_members where user_id = :id", mapOf("id" to userId))
        as_("admin").put("/api/libraries/$alpha/members/$userId", mapOf("role" to "viewer"))
        val forUser = as_("user").get("/api/lists/$shared").json
        assertEquals(listOf(alphaBook), forUser.path("books").values().map { it.path("book").path("id").asLong() })
        assertEquals(1, forUser.path("list").path("bookCount").asInt())
    }

    @Test
    fun `one review per user and book, which its author or a curator removes`() {
        val reader = as_("reader")
        assertEquals(400, reader.put("/api/books/$betaBook/reviews/mine", mapOf("rating" to 6)).status)
        assertEquals(400, reader.put("/api/books/$betaBook/reviews/mine", mapOf("body" to "  ")).status)
        assertEquals(200, reader.put("/api/books/$betaBook/reviews/mine", mapOf("rating" to 3, "body" to "fine")).status)
        val updated = reader.put("/api/books/$betaBook/reviews/mine", mapOf("rating" to 5, "body" to "great after all")).json
        val curatorReview = as_("curator").put("/api/books/$betaBook/reviews/mine", mapOf("rating" to 4)).json

        val reviews = reader.get("/api/books/$betaBook/reviews").json
        assertEquals(2, reviews.path("count").asInt())
        assertEquals(4.5, reviews.path("averageRating").asDouble())
        assertEquals("great after all", reviews.path("mine").path("body").asString())
        assertFalse(reviews.path("canModerate").asBoolean())

        assertEquals(403, reader.delete("/api/reviews/${curatorReview.path("id").asLong()}").status)
        assertTrue(as_("curator").get("/api/books/$betaBook/reviews").json.path("canModerate").asBoolean())
        assertEquals(204, as_("curator").delete("/api/reviews/${updated.path("id").asLong()}").status)
        assertEquals(1, reader.get("/api/books/$betaBook/reviews").json.path("count").asInt())
        assertEquals(204, as_("curator").delete("/api/books/$betaBook/reviews/mine").status)
    }

    @Test
    fun `personal API keys get the MCP tools and REST calls of their scopes`() {
        val reader = as_("reader")
        val listId = reader.post("/api/lists", mapOf("name" to "For the agent", "shared" to false)).json.path("id").asLong()
        reader.put("/api/lists/$listId/books/$alphaBook")

        val created = reader.post("/api/me/api-keys", mapOf("name" to "agent", "scopes" to listOf("library:read", "favorites:read")))
        assertEquals(201, created.status, created.body)
        val key = created.json.path("rawKey").asString()
        assertTrue(key.startsWith("rlk_"))
        assertEquals(key.take(8), created.json.path("apiKey").path("keyPrefix").asString())

        val tools = reader.tools(key)
        assertTrue(listOf("search_library", "ask_library", "list_books", "list_favorite_lists", "get_favorite_list").all { it in tools }, "$tools")
        assertTrue(listOf("create_favorite_list", "add_to_favorite_list", "get_book_reviews", "write_book_review").none { it in tools }, "$tools")

        val lists = reader.callTool("list_favorite_lists", emptyMap(), key)
        assertFalse(lists.path("isError").asBoolean(), lists.toString())
        assertTrue(lists.toString().contains("For the agent"))
        val contents = reader.callTool("get_favorite_list", mapOf("listId" to listId), key)
        assertTrue(contents.toString().contains("Alpha Book"), contents.toString())
        // a tool outside the key's scopes fails even when called by name
        assertTrue(reader.callTool("create_favorite_list", mapOf("name" to "nope"), key).path("isError").asBoolean())
        val books = reader.callTool("list_books", emptyMap(), key)
        assertTrue(books.toString().contains("Alpha Book") && books.toString().contains("Beta Book"))

        // the same key on the REST API: reading is in scope, writing and key management aren't
        val anon = Browser(base)
        assertEquals(200, anon.get("/api/lists", key = key).status)
        assertEquals(200, anon.get("/api/books/$alphaBook", key = key).status)
        assertEquals(403, anon.post("/api/lists", mapOf("name" to "x"), key = key).status)
        assertEquals(403, anon.get("/api/me/api-keys", key = key).status)
        assertEquals(403, anon.get("/api/books/$alphaBook/reviews", key = key).status)
        assertEquals(true, anon.get("/api/me", key = key).json.path("viaApiKey").asBoolean())

        // a session can't call MCP (keys only)
        assertEquals(403, reader.mcp("tools/list", key = null).status)

        // revoked: refused at once
        val id = created.json.path("apiKey").path("id").asString()
        assertTrue(reader.get("/api/me/api-keys").json.values().any { it.path("id").asString() == id })
        assertEquals(204, reader.delete("/api/me/api-keys/$id").status)
        assertEquals(401, anon.mcp("tools/list", key = key).status)
        assertEquals(401, anon.get("/api/lists", key = key).status)
    }

    @Test
    fun `a personal key restricted to one library, and an expired key`() {
        val curator = as_("curator")
        val key = curator.post("/api/me/api-keys", mapOf("name" to "alpha only", "scopes" to listOf("library:read"), "libraryIds" to listOf(alpha)))
            .json.path("rawKey").asString()
        val libs = curator.callTool("list_libraries", emptyMap(), key).toString()
        assertTrue(libs.contains("Alpha") && !libs.contains("Beta"), libs)
        assertEquals(404, Browser(base).get("/api/books/$betaBook", key = key).status)

        val expiring = curator.post("/api/me/api-keys", mapOf("name" to "soon", "scopes" to listOf("library:read"),
            "expiresAt" to Instant.now().plusSeconds(3600).toString())).json
        val raw = expiring.path("rawKey").asString()
        assertEquals(200, Browser(base).get("/api/me", key = raw).status)
        jdbc.update("update api_keys set expires_at = now() - interval '1 second' where id = cast(:id as uuid)",
            mapOf("id" to expiring.path("apiKey").path("id").asString()))
        assertEquals(401, Browser(base).get("/api/me", key = raw).status)
        assertEquals(400, curator.post("/api/me/api-keys", mapOf("name" to "past", "scopes" to listOf("library:read"),
            "expiresAt" to Instant.now().minusSeconds(60).toString())).status)
    }

    @Test
    fun `system-wide keys are an admin's, act as no user, and can be limited to libraries`() {
        val admin = as_("admin")
        assertEquals(403, as_("librarian").post("/api/admin/api-keys", mapOf("name" to "x", "scopes" to listOf("library:read"))).status)
        assertEquals(403, as_("reader").get("/api/admin/users").status)
        assertEquals(400, admin.post("/api/admin/api-keys", mapOf("name" to "x", "scopes" to listOf("favorites:read"))).status)

        val created = admin.post("/api/admin/api-keys", mapOf("name" to "wiki bot", "scopes" to listOf("library:read"), "libraryIds" to listOf(beta)))
        assertEquals(201, created.status, created.body)
        assertTrue(created.json.path("apiKey").path("system").asBoolean())
        val key = created.json.path("rawKey").asString()

        val tools = admin.tools(key)
        assertEquals(listOf("ask_library", "library_status", "list_books", "list_libraries", "list_tags", "search_library"), tools)
        val libs = admin.callTool("list_libraries", emptyMap(), key).toString()
        assertTrue(libs.contains("Beta") && !libs.contains("Alpha"), libs)
        assertTrue(admin.callTool("list_favorite_lists", emptyMap(), key).path("isError").asBoolean())

        val anon = Browser(base)
        assertEquals(403, anon.get("/api/lists", key = key).status)
        assertEquals(200, anon.get("/api/books/$betaBook", key = key).status)
        assertEquals(404, anon.get("/api/books/$alphaBook", key = key).status)
        assertTrue(anon.get("/api/me", key = key).json.path("id").isNull)

        assertTrue(admin.get("/api/admin/api-keys").json.values().any { it.path("name").asString() == "wiki bot" })
        // admin endpoints need a browser session, even for an admin's own key
        val adminKey = admin.post("/api/me/api-keys", mapOf("name" to "admin's", "scopes" to listOf("library:read", "library:curate"))).json.path("rawKey").asString()
        assertEquals(403, anon.get("/api/admin/users", key = adminKey).status)

        // the configured key is a system key too
        assertEquals(tools, anon.tools(STATIC_KEY))
        assertTrue(anon.callTool("list_libraries", emptyMap(), STATIC_KEY).toString().let { it.contains("Alpha") && it.contains("Beta") })
    }

    @Test
    fun `a disabled user is signed out and its keys stop working, and admins can not lock themselves out`() {
        val admin = as_("admin")
        val adminId = meOf("admin").path("id").asLong()
        assertEquals(400, admin.put("/api/admin/users/$adminId", mapOf("disabled" to true)).status)

        val victim = Browser(base).login("curator@raggedlib.dev", "curator")
        val id = victim.get("/api/me").json.path("id").asLong()
        val key = victim.post("/api/me/api-keys", mapOf("name" to "k", "scopes" to listOf("library:read"))).json.path("rawKey").asString()
        assertEquals(200, Browser(base).get("/api/me", key = key).status)

        assertEquals(200, admin.put("/api/admin/users/$id", mapOf("disabled" to true)).status)
        Thread.sleep(5_100) // the role cache
        assertEquals(401, victim.get("/api/me").status)
        assertEquals(401, Browser(base).get("/api/me", key = key).status)
        val refused = runCatching { Browser(base).login("curator@raggedlib.dev", "curator") }
        assertTrue(refused.exceptionOrNull()?.message?.contains("account_disabled") == true, "$refused")

        assertEquals(200, admin.put("/api/admin/users/$id", mapOf("disabled" to false)).status)
        sessions.remove("curator")
        assertNotNull(as_("curator"))
        val users = admin.get("/api/admin/users").json.values().map { it.path("displayName").asString() }
        assertTrue(users.containsAll(listOf("admin", "curator")), "$users")
    }
}
