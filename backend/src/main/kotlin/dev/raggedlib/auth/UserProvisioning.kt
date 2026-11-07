package dev.raggedlib.auth

import dev.raggedlib.config.RaggedlibProperties
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component

/**
 * Signs a user in: loads the OIDC user as Spring Security does, then creates or refreshes its `users` row (profile,
 * groups, the admin claim) and returns a [RaggedlibOidcUser] with the app's authorities. A disabled user is refused.
 */
@Component
class UserProvisioning(
    private val users: UserRepository,
    private val access: Access,
    props: RaggedlibProperties,
) : OAuth2UserService<OidcUserRequest, OidcUser> {
    private val log = LoggerFactory.getLogger(javaClass)
    private val delegate = OidcUserService()
    private val auth = props.auth
    private val adminClaim = auth.adminClaim.ifBlank { auth.groupsClaim }

    override fun loadUser(userRequest: OidcUserRequest): OidcUser {
        val oidc = delegate.loadUser(userRequest)
        val claims = oidc.claims
        val groups = Claims.strings(claims, auth.groupsClaim)
        val admin = Claims.matches(claims, adminClaim, auth.adminValues)
        val name = oidc.fullName?.takeIf { it.isNotBlank() }
            ?: oidc.preferredUsername?.takeIf { it.isNotBlank() }
            ?: oidc.getClaimAsString("name")
        val user = users.upsertFromLogin(
            issuer = oidc.issuer?.toString() ?: userRequest.clientRegistration.providerDetails.issuerUri ?: "unknown",
            subject = oidc.subject ?: throw OAuth2AuthenticationException(OAuth2Error("invalid_id_token", "The ID token has no subject.", null)),
            email = oidc.email,
            displayName = name,
            groups = groups,
            adminFromClaim = admin,
        )
        access.invalidate(user.id)
        if (user.disabled) {
            log.info("Refused sign-in of disabled user #{} ({})", user.id, user.label)
            throw OAuth2AuthenticationException(OAuth2Error("account_disabled", "This account is disabled.", null))
        }
        log.info("Signed in: user #{} ({}){}", user.id, user.label, if (user.admin) ", admin" else "")
        return RaggedlibOidcUser(user.id, oidc.authorities + sessionAuthorities(user.admin), oidc.idToken, oidc.userInfo)
    }
}
