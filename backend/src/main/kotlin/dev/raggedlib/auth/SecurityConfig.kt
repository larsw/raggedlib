package dev.raggedlib.auth

import dev.raggedlib.config.RaggedlibProperties
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.authorization.AuthorizationManagers
import org.springframework.security.authorization.AuthorityAuthorizationManager.hasAuthority
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler
import org.springframework.security.web.authentication.AuthenticationFilter
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.authentication.logout.LogoutFilter
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.security.web.util.matcher.AnyRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration

/**
 * Who may call what, with standard Spring Security:
 *
 * - **Browser sessions** sign in with OIDC (`oauth2Login`): `/api/auth/login/{registration}` starts it, the provider
 *   redirects back to `/api/auth/callback/{registration}`, and `POST /api/auth/logout` ends the session. The web UI
 *   proxies `/api`, so all of this happens on its origin. Cookie-authenticated writes need the CSRF token (SPA style:
 *   cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`).
 * - **API keys** (`Authorization: Bearer` or `X-API-Key`) authenticate `/mcp` and `/api` alike; their scopes become
 *   `SCOPE_*` authorities, which the rules below check. A session has every scope.
 * - **Single-user mode** (`raggedlib.auth.mode=single-user`, see [AuthMode]): every request without an API key is the
 *   local admin.
 *
 * Which libraries and books a caller may touch is decided per request by [Access].
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun apiKeyAuthenticationProvider(keys: ApiKeyService, users: UserRepository) = ApiKeyAuthenticationProvider(keys, users)

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        clientRegistrations: ObjectProvider<ClientRegistrationRepository>,
        apiKeyProvider: ApiKeyAuthenticationProvider,
        provisioning: UserProvisioning,
        users: UserRepository,
        access: Access,
        mapper: ObjectMapper,
        props: RaggedlibProperties,
    ): SecurityFilterChain {
        val mode = AuthMode.parse(props.auth.mode)
        // in single-user mode this is SingleUserModeConfig's empty repository, which lists nothing
        val registrations = clientRegistrations.getIfAvailable()
        val registration = (registrations as? Iterable<*>)?.filterIsInstance<ClientRegistration>()?.firstOrNull()
        check(mode != AuthMode.OIDC || registration != null) {
            "${AuthMode.PROPERTY}=oidc, but no OIDC client is configured: activate the `oidc` profile (with OIDC_ISSUER_URI, " +
                "OIDC_CLIENT_ID, OIDC_CLIENT_SECRET) or `dex`, or set ${AuthMode.PROPERTY}=single-user for no sign-in"
        }
        val loginUrl = registration?.let { "$LOGIN_BASE/${it.registrationId}" }
        val errors = JsonErrors(mapper, loginUrl)

        val apiKeyFilter = AuthenticationFilter(ProviderManager(apiKeyProvider), ApiKeyAuthenticationConverter()).apply {
            successHandler = AuthenticationSuccessHandler { _, _, _ -> } // carry on with the request
            failureHandler = AuthenticationEntryPointFailureHandler(errors.keyEntryPoint)
        }
        val keyRequest = RequestMatcher(ApiKeyAuthenticationConverter::hasKeyHeader)
        val mcp = RequestMatcher { r: HttpServletRequest -> r.servletPath == "/mcp" || r.servletPath.startsWith("/mcp/") }

        http
            .cors(Customizer.withDefaults())
            .csrf { it.spa().ignoringRequestMatchers(keyRequest, mcp) }
            .requestCache { it.requestCache(NullRequestCache()) }
            .addFilterAfter(apiKeyFilter, LogoutFilter::class.java)
            .addFilterAfter(CsrfCookieFilter(), BasicAuthenticationFilter::class.java)
            .addFilterBefore(CallerFilter(access), AuthorizationFilter::class.java)
            .exceptionHandling {
                it.defaultAuthenticationEntryPointFor(errors.keyEntryPoint, RequestMatcher { r -> mcp.matches(r) || keyRequest.matches(r) })
                it.defaultAuthenticationEntryPointFor(errors.apiEntryPoint, AnyRequestMatcher.INSTANCE)
                it.accessDeniedHandler(errors.accessDenied)
            }
            .logout {
                it.logoutUrl("/api/auth/logout")
                it.logoutSuccessHandler(HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
            }
            .authorizeHttpRequests { a ->
                a.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error").permitAll()
                a.requestMatchers("/api/auth/**").permitAll()
                // MCP is for API keys; a tool checks its own scope (LibraryTools, UserTools)
                a.requestMatchers(mcp).hasAuthority(Authorities.API_KEY)
                a.requestMatchers("/api/me").authenticated()
                // managing keys, users and members needs a browser session, never another key
                a.requestMatchers("/api/me/api-keys", "/api/me/api-keys/**").hasAuthority(Authorities.SESSION)
                a.requestMatchers("/api/admin/**").access(all(hasAuthority(Authorities.SESSION), hasAuthority(Authorities.ADMIN)))
                a.requestMatchers("/api/libraries/*/members", "/api/libraries/*/members/**").hasAuthority(Authorities.SESSION)
                // favorite lists and reviews: per-user features, so never a system-wide key (it has no such scopes)
                a.requestMatchers(HttpMethod.GET, "/api/lists", "/api/lists/**").hasAuthority(ApiScope.FAVORITES_READ.authority)
                a.requestMatchers("/api/lists", "/api/lists/**").hasAuthority(ApiScope.FAVORITES_WRITE.authority)
                a.requestMatchers(HttpMethod.GET, "/api/books/*/reviews", "/api/me/reviews").hasAuthority(ApiScope.REVIEWS_READ.authority)
                a.requestMatchers("/api/books/*/reviews", "/api/books/*/reviews/**", "/api/reviews/**").hasAuthority(ApiScope.REVIEWS_WRITE.authority)
                // curating: syncing, indexing (the libraries are checked by Access)
                a.requestMatchers(HttpMethod.POST, "/api/library/sync", "/api/books/*/reindex", "/api/ingest/**", "/api/vectors/book-vectors")
                    .hasAuthority(ApiScope.LIBRARY_CURATE.authority)
                a.requestMatchers(HttpMethod.PUT, "/api/settings")
                    .access(all(hasAuthority(Authorities.ADMIN), hasAuthority(ApiScope.LIBRARY_CURATE.authority)))
                a.requestMatchers("/api/**").hasAuthority(ApiScope.LIBRARY_READ.authority)
                a.anyRequest().denyAll()
            }

        if (mode == AuthMode.OIDC && registration != null) {
            val home = props.auth.postLoginRedirect
            http.oauth2Login { o ->
                o.loginPage(loginUrl)
                o.authorizationEndpoint { it.baseUri(LOGIN_BASE) }
                o.redirectionEndpoint { it.baseUri("/api/auth/callback/*") }
                o.tokenEndpoint { it.accessTokenResponseClient(tokenResponseClient()) }
                o.userInfoEndpoint { it.oidcUserService(provisioning) }
                o.successHandler(SimpleUrlAuthenticationSuccessHandler(home).apply { setAlwaysUseDefaultTargetUrl(true) })
                o.failureHandler { _, response, e ->
                    val code = (e as? OAuth2AuthenticationException)?.error?.errorCode ?: "login_failed"
                    log.info("Sign-in failed: {}", e.message)
                    response.sendRedirect(UriComponentsBuilder.fromUriString(home).queryParam("login_error", code).build().toUriString())
                }
            }
            log.info("Sign-in through OIDC provider '{}' ({})", registration.registrationId, registration.providerDetails.issuerUri)
        } else {
            http.addFilterBefore(LocalUserFilter(users, mcp), AnonymousAuthenticationFilter::class.java)
            log.warn(
                "SINGLE-USER mode (${AuthMode.PROPERTY}=single-user): no sign-in, every request without an API key acts " +
                    "as the local admin. Don't publish the backend beyond localhost like this; for sign-in set " +
                    "${AuthMode.PROPERTY}=oidc with the `oidc` (or `dex`) profile.",
            )
        }
        return http.build()
    }

    private fun all(vararg managers: AuthorizationManager<RequestAuthorizationContext>): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.allOf(*managers)

    /**
     * Exchanges the sign-in code for tokens over the JDK's HttpClient, configured otherwise like Spring Security's
     * default. That default is a RestClient, which picks Reactor Netty (on the classpath for Spring AI); Netty's own DNS
     * resolver sends every `*.localhost` name to the loopback address (RFC 6761) without asking the system's resolver.
     * In a container, the development dex's `dex.localhost` (a Docker network alias, see docker-compose.yml) thus
     * became 127.0.0.1 and every sign-in failed with `invalid_token_response`. Discovery, the user-info call and the
     * JWK set already go through the system resolver (RestTemplate), so all calls to the provider now resolve alike.
     */
    private fun tokenResponseClient() = RestClientAuthorizationCodeTokenResponseClient().apply {
        val http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // no h2c upgrade attempts against plain-http providers
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        setRestClient(
            RestClient.builder()
                .requestFactory(JdkClientHttpRequestFactory(http).apply { setReadTimeout(Duration.ofSeconds(30)) })
                .configureMessageConverters { c ->
                    c.addCustomConverter(FormHttpMessageConverter())
                    c.addCustomConverter(OAuth2AccessTokenResponseHttpMessageConverter())
                }
                .defaultStatusHandler(OAuth2ErrorResponseErrorHandler())
                .build(),
        )
    }

    companion object {
        const val LOGIN_BASE = "/api/auth/login"
    }
}
