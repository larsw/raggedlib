package dev.raggedlib.auth

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser

/** The signed-in user of a browser session: the OIDC user plus the id of its `users` row. */
class RaggedlibOidcUser(
    val userId: Long,
    authorities: Collection<GrantedAuthority>,
    idToken: OidcIdToken,
    userInfo: OidcUserInfo?,
) : DefaultOidcUser(authorities, idToken, userInfo, "sub") {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * An API key: unauthenticated it carries the key as presented (credentials), authenticated the [ApiKeyPrincipal]
 * it resolved to.
 */
class ApiKeyAuthenticationToken private constructor(
    private val principal: Any,
    private var rawKey: String?,
    authorities: Collection<GrantedAuthority>,
    authenticated: Boolean,
) : AbstractAuthenticationToken(authorities) {
    init {
        super.setAuthenticated(authenticated)
    }

    override fun getPrincipal(): Any = principal
    override fun getCredentials(): Any? = rawKey
    override fun getName(): String = (principal as? ApiKeyPrincipal)?.name ?: "api-key"

    override fun setAuthenticated(authenticated: Boolean) {
        require(!authenticated) { "use ApiKeyAuthenticationToken.authenticated(...)" }
        super.setAuthenticated(false)
    }

    override fun eraseCredentials() {
        super.eraseCredentials()
        rawKey = null
    }

    val apiKey: ApiKeyPrincipal? get() = principal as? ApiKeyPrincipal

    companion object {
        private const val serialVersionUID = 1L

        fun unauthenticated(rawKey: String) = ApiKeyAuthenticationToken("api-key", rawKey, emptyList(), false)

        fun authenticated(key: ApiKeyPrincipal, admin: Boolean): ApiKeyAuthenticationToken {
            val authorities = buildList {
                add(SimpleGrantedAuthority(Authorities.API_KEY))
                if (key.userId != null) add(SimpleGrantedAuthority(Authorities.USER))
                if (admin) add(SimpleGrantedAuthority(Authorities.ADMIN))
                key.scopes.forEach { add(SimpleGrantedAuthority(it.authority)) }
            }
            return ApiKeyAuthenticationToken(key, null, authorities, true)
        }
    }
}

/** Single-user mode (no OIDC configured): every request without an API key is the built-in local admin. */
class LocalUserAuthentication(val userId: Long) : AbstractAuthenticationToken(sessionAuthorities(admin = true)) {
    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): Any = "local"
    override fun getCredentials(): Any? = null
    override fun getName(): String = "local"

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** What a browser session may do: everything a user may (every scope), gated by its roles. */
fun sessionAuthorities(admin: Boolean): List<GrantedAuthority> = buildList {
    add(SimpleGrantedAuthority(Authorities.USER))
    add(SimpleGrantedAuthority(Authorities.SESSION))
    if (admin) add(SimpleGrantedAuthority(Authorities.ADMIN))
    ApiScope.entries.forEach { add(SimpleGrantedAuthority(it.authority)) }
}
