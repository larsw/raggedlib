package dev.raggedlib.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.AuthenticationConverter
import org.springframework.security.web.csrf.CsrfException
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/** Reads an API key from `Authorization: Bearer <key>` or `X-API-Key: <key>`; null when the request has neither. */
class ApiKeyAuthenticationConverter : AuthenticationConverter {
    override fun convert(request: HttpServletRequest): Authentication? =
        presentedKey(request)?.let { ApiKeyAuthenticationToken.unauthenticated(it) }

    companion object {
        fun presentedKey(request: HttpServletRequest): String? {
            request.getHeader("X-API-Key")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val auth = request.getHeader(HttpHeaders.AUTHORIZATION)?.trim() ?: return null
            return if (auth.length > 7 && auth.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) {
                auth.substring(7).trim().takeIf { it.isNotEmpty() }
            } else null
        }

        /** Requests that carry an API key header (they never rely on cookies, so CSRF doesn't apply). */
        fun hasKeyHeader(request: HttpServletRequest): Boolean =
            request.getHeader("X-API-Key") != null ||
                request.getHeader(HttpHeaders.AUTHORIZATION)?.trim()?.startsWith("Bearer ", ignoreCase = true) == true
    }
}

/** Authenticates an [ApiKeyAuthenticationToken] against [ApiKeyService]. */
class ApiKeyAuthenticationProvider(
    private val keys: ApiKeyService,
    private val users: UserRepository,
) : AuthenticationProvider {
    override fun authenticate(authentication: Authentication): Authentication {
        val raw = authentication.credentials as? String ?: throw BadCredentialsException("missing API key")
        val key = keys.authenticate(raw) ?: throw BadCredentialsException("invalid, expired or revoked API key")
        val admin = key.userId?.let { users.find(it)?.admin } ?: false
        return ApiKeyAuthenticationToken.authenticated(key, admin)
    }

    override fun supports(authentication: Class<*>): Boolean = ApiKeyAuthenticationToken::class.java.isAssignableFrom(authentication)
}

/** JSON error bodies for the security layer, shaped like the API's own (`{"error": "..."}`). */
class JsonErrors(private val mapper: ObjectMapper, private val loginUrl: String?) {
    /** 401 for the web API: tells the UI where to sign in. */
    val apiEntryPoint = AuthenticationEntryPoint { _, response, _ ->
        write(response, HttpServletResponse.SC_UNAUTHORIZED, buildMap {
            put("error", "sign in first")
            if (loginUrl != null) put("loginUrl", loginUrl)
        })
    }

    /** 401 for API-key clients (MCP): a bearer challenge. */
    val keyEntryPoint = AuthenticationEntryPoint { _, response, e: AuthenticationException? ->
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"raggedlib\"")
        val reason = if (e is BadCredentialsException) e.message ?: "invalid API key" else "missing API key"
        write(response, HttpServletResponse.SC_UNAUTHORIZED, mapOf(
            "error" to "unauthorized",
            "message" to "$reason; send Authorization: Bearer <key> or X-API-Key: <key>",
        ))
    }

    val accessDenied = AccessDeniedHandler { _, response, e ->
        val auth = SecurityContextHolder.getContext().authentication
        val msg = when {
            e is CsrfException -> "missing or invalid CSRF token (X-XSRF-TOKEN); reload the page"
            auth is ApiKeyAuthenticationToken -> "this API key may not do this: it lacks the scope, or it needs a browser session"
            else -> "you may not do this"
        }
        write(response, HttpServletResponse.SC_FORBIDDEN, mapOf("error" to msg))
    }

    private fun write(response: HttpServletResponse, status: Int, body: Map<String, String>) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(mapper.writeValueAsString(body))
    }
}

/**
 * Single-user mode: authenticates every request that carries no API key as the built-in local admin, so the app
 * works as it did before multi-user support. Not MCP requests: MCP takes API keys only, and a client without one
 * should get the bearer challenge.
 */
class LocalUserFilter(private val users: UserRepository, private val mcp: RequestMatcher) : OncePerRequestFilter() {
    @Volatile private var localUserId: Long? = null

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val context = SecurityContextHolder.getContext()
        if (context.authentication == null && !ApiKeyAuthenticationConverter.hasKeyHeader(request) && !mcp.matches(request)) {
            val id = localUserId ?: users.localUser().id.also { localUserId = it }
            context.authentication = LocalUserAuthentication(id)
        }
        chain.doFilter(request, response)
    }
}

/**
 * Ends the session of a user an admin disabled (API keys of disabled users are refused when authenticating), and
 * puts the caller in the logging MDC as `caller` for the rest of the request.
 */
class CallerFilter(private val access: Access) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val caller = access.callerOrNull()
        val userId = caller?.userId
        if (userId != null && access.state(userId).user?.disabled != false) {
            log.info("Request of disabled or deleted user #{} refused", userId)
            request.getSession(false)?.invalidate()
            SecurityContextHolder.clearContext()
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write("""{"error":"this account is disabled","code":"account_disabled"}""")
            return
        }
        if (caller != null) MDC.put(MDC_KEY, caller.label)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val MDC_KEY = "caller"
    }
}

/**
 * Makes sure the `XSRF-TOKEN` cookie is set, so the web UI can echo it in `X-XSRF-TOKEN` (Spring Security creates
 * the token lazily, i.e. only once something reads it).
 */
class CsrfCookieFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
        chain.doFilter(request, response)
    }
}
