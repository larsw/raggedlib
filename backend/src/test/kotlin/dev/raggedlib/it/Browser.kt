package dev.raggedlib.it

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * A minimal browser for integration tests: a cookie jar, manual redirects, and dex's password form. Speaks to the
 * backend directly (no web UI proxy), the way the UI's proxy does.
 */
class Browser(private val base: String) {
    val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val http: HttpClient = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build()

    data class Response(val status: Int, val body: String, val headers: Map<String, List<String>>) {
        val json: JsonNode by lazy { MAPPER.readTree(body.ifEmpty { "null" }) }
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    /** Signs in through the OIDC flow: app -> dex login form -> dex -> app callback -> the app's home. */
    fun login(email: String, password: String): Browser {
        var res = raw(HttpRequest.newBuilder(URI("$base/api/auth/login/oidc")).GET())
        check(res.status == 302) { "login start: ${res.status} ${res.body}" }
        var url = URI(base).resolve(res.header("Location")!!)
        repeat(12) {
            res = raw(HttpRequest.newBuilder(url).GET())
            if (res.status == 200 && res.body.contains("name=\"password\"")) {
                val action = FORM_ACTION.find(res.body)?.groupValues?.get(1)?.replace("&amp;", "&")?.takeIf { it.isNotEmpty() }
                val target = action?.let(url::resolve) ?: url
                res = raw(
                    HttpRequest.newBuilder(target)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form("login" to email, "password" to password))),
                )
                check(res.status in 300..399) { "dex refused $email: ${res.status} ${res.body.take(500)}" }
            } else {
                check(res.status in 300..399) { "unexpected ${res.status} at $url: ${res.body.take(500)}" }
            }
            url = url.resolve(res.header("Location")!!)
            // back home: signed in (or refused, with ?login_error=...)
            if (url.toString().startsWith(base) && !url.path.startsWith("/api/auth/")) {
                check(url.query?.contains("login_error") != true) { "sign-in of $email failed: $url" }
                return this
            }
        }
        error("sign-in of $email did not come back to the app")
    }

    fun get(path: String, key: String? = null) = send("GET", path, null, key)
    fun post(path: String, body: Any? = null, key: String? = null, csrf: Boolean = true) = send("POST", path, body, key, csrf)
    fun put(path: String, body: Any? = null, key: String? = null, csrf: Boolean = true) = send("PUT", path, body, key, csrf)
    fun delete(path: String, key: String? = null, csrf: Boolean = true) = send("DELETE", path, null, key, csrf)

    /** A JSON-RPC call to the MCP endpoint with an API key (or none). */
    fun mcp(method: String, params: Map<String, Any?> = emptyMap(), key: String?): Response {
        val b = HttpRequest.newBuilder(URI("$base/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))))
        if (key != null) b.header("Authorization", "Bearer $key")
        return raw(b)
    }

    fun tools(key: String): List<String> {
        val res = mcp("tools/list", key = key)
        check(res.status == 200) { "tools/list: ${res.status} ${res.body}" }
        return res.json.path("result").path("tools").values().map { it.path("name").asString() }.sorted()
    }

    /** tools/call: the result node (`isError`, `content`, `structuredContent`). */
    fun callTool(name: String, args: Map<String, Any?>, key: String): JsonNode {
        val res = mcp("tools/call", mapOf("name" to name, "arguments" to args), key)
        check(res.status == 200) { "tools/call $name: ${res.status} ${res.body}" }
        return res.json.path("result").takeIf { !it.isMissingNode } ?: error("no result: ${res.body}")
    }

    fun xsrfToken(): String? = cookies.cookieStore.cookies.firstOrNull { it.name == "XSRF-TOKEN" }?.value

    private fun send(method: String, path: String, body: Any?, key: String?, csrf: Boolean = false): Response {
        val b = HttpRequest.newBuilder(URI(base + path)).header("Accept", "application/json")
        if (key != null) b.header("Authorization", "Bearer $key")
        if (csrf && key == null) {
            // the UI reads the XSRF-TOKEN cookie (set on any API call) and echoes it
            if (xsrfToken() == null) get("/api/me")
            xsrfToken()?.let { b.header("X-XSRF-TOKEN", it) }
        }
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody()
        else HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)).also { b.header("Content-Type", "application/json") }
        return raw(b.method(method, publisher))
    }

    private fun raw(b: HttpRequest.Builder): Response {
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return Response(r.statusCode(), r.body(), r.headers().map())
    }

    private fun form(vararg fields: Pair<String, String>) =
        fields.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }

    companion object {
        val MAPPER: ObjectMapper = JsonMapper.builder().build()
        private val FORM_ACTION = Regex("""<form[^>]*action="([^"]*)"""", RegexOption.IGNORE_CASE)
    }
}
