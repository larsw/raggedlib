package dev.raggedlib.auth

import dev.raggedlib.config.ApiException
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.config.notFound
import dev.raggedlib.library.BookRepository
import dev.raggedlib.library.BookRow
import dev.raggedlib.library.LibraryInfo
import dev.raggedlib.library.LibraryRegistry
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Who is making the current request. */
data class Caller(
    /** The user acting (session or personal API key); null for a system-wide API key. */
    val userId: Long?,
    val scopes: Set<ApiScope>,
    /** An API key's own restriction to some libraries; null = none. */
    val keyLibraries: Set<Long>?,
    val viaApiKey: Boolean,
    /** For logs. */
    val label: String,
)

/** Members of [group] have [role] in [library] (a name, an id, or `*`). */
data class GroupRule(val group: String, val role: LibraryRole, val library: String) {
    fun covers(lib: LibraryInfo) = library == "*" || library.equals(lib.name, ignoreCase = true) || library == lib.id.toString()
}

/** Effective library roles, from everything that grants one. Pure, for testing. */
object RoleResolver {
    fun userRoles(
        user: UserRow,
        memberships: Collection<Membership>,
        libraries: Collection<LibraryInfo>,
        defaultRole: LibraryRole,
        groupRules: Collection<GroupRule>,
    ): Map<Long, LibraryRole> {
        if (user.disabled) return libraries.associate { it.id to LibraryRole.NONE }
        if (user.admin) return libraries.associate { it.id to LibraryRole.ADMIN }
        val granted = memberships.associate { it.libraryId to it.role }
        val groups = user.groups.toSet()
        return libraries.associate { lib ->
            var role = LibraryRole.max(defaultRole, granted[lib.id] ?: LibraryRole.NONE)
            for (rule in groupRules) if (rule.group in groups && rule.covers(lib)) role = LibraryRole.max(role, rule.role)
            lib.id to role
        }
    }

    /** A system-wide key acts as viewer, or as curator with `library:curate`; never as library admin. */
    fun systemRoles(scopes: Set<ApiScope>, libraries: Collection<LibraryInfo>): Map<Long, LibraryRole> {
        val role = if (ApiScope.LIBRARY_CURATE in scopes) LibraryRole.CURATOR else LibraryRole.VIEWER
        return libraries.associate { it.id to role }
    }

    /** An API key restricted to some libraries has no role in the others. */
    fun restrict(roles: Map<Long, LibraryRole>, keyLibraries: Set<Long>?): Map<Long, LibraryRole> =
        if (keyLibraries == null) roles else roles.mapValues { (id, r) -> if (id in keyLibraries) r else LibraryRole.NONE }
}

/**
 * Authorization in terms of libraries and books, for the current request: which libraries the caller can see,
 * curate or administer, and whether it may touch a book. Spring Security's URL rules (SecurityConfig) decide which
 * endpoints a caller may call at all (authentication, scopes, admin); this decides on which data.
 *
 * A caller without access to a library is told it doesn't exist (404), so library and book ids don't leak.
 */
@Component
class Access(
    private val users: UserRepository,
    private val registry: LibraryRegistry,
    private val books: BookRepository,
    props: RaggedlibProperties,
) {
    val defaultRole: LibraryRole = LibraryRole.parse(props.auth.defaultLibraryRole).also {
        require(it != LibraryRole.ADMIN) { "raggedlib.auth.default-library-role can be none, viewer or curator, not admin" }
    }

    val groupRules: List<GroupRule> = props.auth.groupRoles.mapIndexed { i, g ->
        require(g.group.isNotBlank()) { "raggedlib.auth.group-roles[$i]: group must not be empty" }
        val role = LibraryRole.parse(g.role)
        require(role != LibraryRole.NONE) { "raggedlib.auth.group-roles[$i]: role must be viewer, curator or admin" }
        GroupRule(g.group.trim(), role, g.library.trim().ifEmpty { "*" })
    }

    private class Cached(val user: UserRow?, val memberships: List<Membership>, val at: Instant)

    private val cache = ConcurrentHashMap<Long, Cached>()

    // ---- the caller -----------------------------------------------------------------------------------------

    /** The current request's caller; 401 if unauthenticated. */
    fun caller(): Caller = callerOrNull() ?: throw ApiException(HttpStatus.UNAUTHORIZED, "sign in first")

    fun callerOrNull(): Caller? = callerOf(SecurityContextHolder.getContext().authentication)

    fun callerOf(auth: Authentication?): Caller? = when {
        auth == null || !auth.isAuthenticated -> null
        auth is ApiKeyAuthenticationToken -> auth.apiKey?.let { k ->
            Caller(k.userId, k.scopes, k.libraryIds, viaApiKey = true, label = "key '${k.name}'")
        }
        auth.principal is RaggedlibOidcUser -> {
            val u = auth.principal as RaggedlibOidcUser
            Caller(u.userId, ApiScope.entries.toSet(), null, viaApiKey = false, label = "user #${u.userId}")
        }
        auth is LocalUserAuthentication -> Caller(auth.userId, ApiScope.entries.toSet(), null, viaApiKey = false, label = "local user")
        else -> null
    }

    /** The user behind the request, for per-user features; 403 for a system-wide key. */
    fun user(): UserRow {
        val c = caller()
        val id = c.userId ?: throw ApiException(HttpStatus.FORBIDDEN, "a system-wide API key acts as no user; use a personal key")
        return state(id).user?.takeIf { !it.disabled } ?: throw ApiException(HttpStatus.FORBIDDEN, "this account is disabled")
    }

    fun userId(): Long = user().id

    fun requireScope(scope: ApiScope) {
        if (scope !in caller().scopes) {
            throw ApiException(HttpStatus.FORBIDDEN, "this API key lacks the '${scope.id}' scope")
        }
    }

    fun hasScope(scope: ApiScope): Boolean = callerOrNull()?.let { scope in it.scopes } ?: false

    /** A global admin (a user, through a session or a personal key). */
    fun isAdmin(): Boolean = callerOrNull()?.userId?.let { state(it).user?.let { u -> u.admin && !u.disabled } } ?: false

    fun requireAdmin() {
        if (!isAdmin()) throw ApiException(HttpStatus.FORBIDDEN, "only an admin can do this")
    }

    // ---- libraries ------------------------------------------------------------------------------------------

    /** The caller's role in every configured library. */
    fun roles(): Map<Long, LibraryRole> = rolesOf(caller())

    fun rolesOf(c: Caller): Map<Long, LibraryRole> {
        val libs = registry.all
        val roles = if (c.userId == null) {
            RoleResolver.systemRoles(c.scopes, libs)
        } else {
            val s = state(c.userId)
            val user = s.user ?: return libs.associate { it.id to LibraryRole.NONE }
            RoleResolver.userRoles(user, s.memberships, libs, defaultRole, groupRules)
        }
        return RoleResolver.restrict(roles, c.keyLibraries)
    }

    fun role(libraryId: Long): LibraryRole = roles()[libraryId] ?: LibraryRole.NONE

    /** Ids of the libraries where the caller's role is at least [role]. */
    fun libraries(role: LibraryRole = LibraryRole.VIEWER): Set<Long> =
        roles().filterValues { it.atLeast(role) }.keys

    /** The libraries the caller can see, out of [requested] (all of them if null). Empty = nothing to see. */
    fun libraryScope(requested: Collection<Long>?): Set<Long> {
        val visible = libraries(LibraryRole.VIEWER)
        return if (requested.isNullOrEmpty()) visible else requested.filterTo(LinkedHashSet()) { it in visible }
    }

    /** 404 if the caller can't see library [libraryId]; 403 if it can, but its role is below [role]. */
    fun requireLibrary(libraryId: Long, role: LibraryRole = LibraryRole.VIEWER): LibraryInfo {
        val lib = registry.all.firstOrNull { it.id == libraryId } ?: notFound("library $libraryId not found")
        val has = role(libraryId)
        if (!has.atLeast(LibraryRole.VIEWER)) notFound("library $libraryId not found")
        if (!has.atLeast(role)) {
            throw ApiException(HttpStatus.FORBIDDEN, "this needs the ${role.name.lowercase()} role in library '${lib.name}'")
        }
        return lib
    }

    /** The book, if the caller can see it (else 404) and has [role] in its library (else 403). */
    fun requireBook(bookId: Long, role: LibraryRole = LibraryRole.VIEWER): BookRow {
        val row = books.find(bookId) ?: notFound("book $bookId not found")
        val has = role(row.book.libraryId)
        if (!has.atLeast(LibraryRole.VIEWER)) notFound("book $bookId not found")
        if (!has.atLeast(role)) {
            throw ApiException(HttpStatus.FORBIDDEN, "this needs the ${role.name.lowercase()} role in the book's library")
        }
        return row
    }

    fun canSeeBook(bookId: Long): Boolean = books.find(bookId)?.let { role(it.book.libraryId).atLeast(LibraryRole.VIEWER) } ?: false

    // ---- user state -----------------------------------------------------------------------------------------

    /** The stored user, briefly cached (a page loads many covers). */
    fun state(userId: Long): CachedUser {
        val now = Instant.now()
        val c = cache[userId]?.takeIf { Duration.between(it.at, now) < TTL }
            ?: Cached(users.find(userId), users.memberships(userId), now).also { cache[userId] = it }
        return CachedUser(c.user, c.memberships)
    }

    /** Forget cached state after a change to a user, or to everyone (null). */
    fun invalidate(userId: Long? = null) {
        if (userId == null) cache.clear() else cache.remove(userId)
    }

    data class CachedUser(val user: UserRow?, val memberships: List<Membership>)

    private companion object {
        val TTL: Duration = Duration.ofSeconds(5)
    }
}
