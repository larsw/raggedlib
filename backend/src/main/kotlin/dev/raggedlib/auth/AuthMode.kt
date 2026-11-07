package dev.raggedlib.auth

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository

/**
 * How the server authenticates users (`raggedlib.auth.mode`, env `RAGGEDLIB_AUTH_MODE`):
 * - [OIDC]: users sign in through an OpenID Connect provider, which must be configured (profile `oidc` or `dex`, which
 *   also set this mode); the server refuses to start without one.
 * - [SINGLE_USER]: no sign-in. Every request without an API key is one built-in local admin, as before multi-user
 *   support; API keys still work. An OIDC client configured anyway is ignored, and its provider isn't contacted.
 */
enum class AuthMode(val id: String) {
    OIDC("oidc"),
    SINGLE_USER("single-user"),
    ;

    companion object {
        const val PROPERTY = "raggedlib.auth.mode"

        fun parse(s: String): AuthMode = entries.firstOrNull { it.id.equals(s.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("$PROPERTY=$s: use ${entries.joinToString(" or ") { it.id }}")
    }
}

/**
 * Single-user mode: an empty client registration repository in place of Spring Boot's, so that an OIDC client left
 * configured (say, the `dex` profile in docker-compose) doesn't make the server discover its provider at startup.
 */
@Configuration
class SingleUserModeConfig {
    @Bean
    @ConditionalOnProperty(name = [AuthMode.PROPERTY], havingValue = "single-user", matchIfMissing = true)
    fun noClientRegistrations(): ClientRegistrationRepository = ClientRegistrationRepository { null }
}
