package dev.raggedlib.auth

import dev.raggedlib.config.ApiException
import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.config.badRequest
import dev.raggedlib.config.notFound
import dev.raggedlib.library.LibraryRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** A library and the caller's role in it. */
data class LibraryRoleView(val id: Long, val name: String, val role: LibraryRole)

/** GET /api/me: who the caller is and what it may do. */
data class MeView(
    /** null for a system-wide API key. */
    val id: Long?,
    val displayName: String,
    val email: String?,
    val admin: Boolean,
    /** `oidc`, or `single-user` when no identity provider is configured (everyone is the local admin). */
    val authMode: String,
    val viaApiKey: Boolean,
    val scopes: List<ApiScope>,
    /** The libraries the caller can see, with its role in each. */
    val libraries: List<LibraryRoleView>,
    /** Curator (or more) in at least one library. */
    val canCurate: Boolean,
    /** Library admin in at least one library (or a global admin). */
    val canManageMembers: Boolean,
)

/** The caller's account and personal API keys. */
@RestController
@RequestMapping("/api/me")
class AccountController(
    private val access: Access,
    private val keys: ApiKeyService,
    private val registry: LibraryRegistry,
    props: RaggedlibProperties,
) {
    private val authMode = AuthMode.parse(props.auth.mode).id

    @GetMapping
    fun me(): MeView {
        val c = access.caller()
        val roles = access.rolesOf(c)
        val libs = registry.all.mapNotNull { lib ->
            roles[lib.id]?.takeIf { it.atLeast(LibraryRole.VIEWER) }?.let { LibraryRoleView(lib.id, lib.name, it) }
        }
        val user = c.userId?.let { access.state(it).user }
        return MeView(
            id = user?.id,
            displayName = user?.label ?: c.label,
            email = user?.email,
            admin = user?.admin == true,
            authMode = authMode,
            viaApiKey = c.viaApiKey,
            scopes = ApiScope.entries.filter { it in c.scopes },
            libraries = libs,
            canCurate = libs.any { it.role.atLeast(LibraryRole.CURATOR) },
            canManageMembers = libs.any { it.role == LibraryRole.ADMIN },
        )
    }

    /** Scopes a key can have, with what each allows. */
    @GetMapping("/api-keys/scopes")
    fun scopes(): List<ScopeView> = ApiScope.entries.map { ScopeView(it, it.description, it.perUser) }

    @GetMapping("/api-keys")
    fun listKeys(): List<ApiKeyView> = keys.personalKeys(access.userId())

    @PostMapping("/api-keys")
    fun createKey(@RequestBody req: ApiKeyRequest): ResponseEntity<CreatedApiKey> =
        ResponseEntity.status(HttpStatus.CREATED).body(keys.createPersonal(access.userId(), req))

    @DeleteMapping("/api-keys/{id}")
    fun revokeKey(@PathVariable id: UUID): ResponseEntity<Void> {
        keys.revokePersonal(access.userId(), id)
        return ResponseEntity.noContent().build()
    }
}

data class ScopeView(val scope: ApiScope, val description: String, val perUser: Boolean)

/** GET /api/admin/users: a user with its roles. */
data class UserView(
    val id: Long,
    val displayName: String,
    val email: String?,
    val groups: List<String>,
    /** Global admin, through the admin claim or granted in the app. */
    val admin: Boolean,
    val adminFromClaim: Boolean,
    val adminGranted: Boolean,
    val disabled: Boolean,
    val createdAt: Instant,
    val lastLoginAt: Instant?,
    /** Roles granted in the app, by library id. */
    val granted: Map<Long, LibraryRole>,
    /** Effective role in every library (granted, through a group, the default, or as admin). */
    val roles: Map<Long, LibraryRole>,
)

data class UserUpdate(val adminGranted: Boolean? = null, val disabled: Boolean? = null)

/** Global admins: users, and system-wide API keys. */
@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val access: Access,
    private val users: UserRepository,
    private val keys: ApiKeyService,
    private val registry: LibraryRegistry,
) {
    @GetMapping("/users")
    fun users(): List<UserView> {
        access.requireAdmin()
        val memberships = users.allMemberships().groupBy { it.userId }
        // the single-user mode's local user too, if the server ever ran that way: its API keys still work until
        // it is disabled
        return users.findAll().map { view(it, memberships[it.id].orEmpty()) }
    }

    @PutMapping("/users/{id}")
    fun update(@PathVariable id: Long, @RequestBody body: UserUpdate): UserView {
        access.requireAdmin()
        val me = access.userId()
        users.find(id) ?: notFound("user $id not found")
        if (id == me && (body.disabled == true || body.adminGranted == false)) {
            badRequest("you can't disable yourself or take away your own admin role")
        }
        body.adminGranted?.let { users.setAdminGranted(id, it) }
        body.disabled?.let { users.setDisabled(id, it) }
        access.invalidate(id)
        return view(users.find(id)!!, users.memberships(id))
    }

    @GetMapping("/api-keys")
    fun systemKeys(): List<ApiKeyView> {
        access.requireAdmin()
        return keys.systemKeys()
    }

    @PostMapping("/api-keys")
    fun createSystemKey(@RequestBody req: ApiKeyRequest): ResponseEntity<CreatedApiKey> {
        access.requireAdmin()
        return ResponseEntity.status(HttpStatus.CREATED).body(keys.createSystem(access.userId(), req))
    }

    @DeleteMapping("/api-keys/{id}")
    fun revokeSystemKey(@PathVariable id: UUID): ResponseEntity<Void> {
        access.requireAdmin()
        keys.revokeSystem(id)
        return ResponseEntity.noContent().build()
    }

    private fun view(u: UserRow, memberships: List<Membership>) = UserView(
        id = u.id,
        displayName = u.label,
        email = u.email,
        groups = u.groups,
        admin = u.admin,
        adminFromClaim = u.adminFromClaim,
        adminGranted = u.adminGranted,
        disabled = u.disabled,
        createdAt = u.createdAt,
        lastLoginAt = u.lastLoginAt,
        granted = memberships.associate { it.libraryId to it.role },
        roles = RoleResolver.userRoles(u, memberships, registry.all, access.defaultRole, access.groupRules),
    )
}

/** A user's role in one library, for the library's admins. */
data class MemberView(
    val userId: Long,
    val displayName: String,
    val email: String?,
    /** Granted in the app for this library; null if none. */
    val granted: LibraryRole?,
    /** What the user has in the library, all sources counted. */
    val role: LibraryRole,
    /** Global admin (ADMIN everywhere, whatever is granted). */
    val admin: Boolean,
    val disabled: Boolean,
)

data class MemberUpdate(val role: String = "")

/** Library admins (and global admins) grant and revoke roles in their library. */
@RestController
@RequestMapping("/api/libraries/{libraryId}/members")
class LibraryMembersController(
    private val access: Access,
    private val users: UserRepository,
) {
    /** Every user, with their role in the library (granting needs to see who there is). */
    @GetMapping
    fun members(@PathVariable libraryId: Long): List<MemberView> {
        val lib = access.requireLibrary(libraryId, LibraryRole.ADMIN)
        val granted = users.membersOf(libraryId).associateBy { it.userId }
        return users.findAll().filter { it.issuer != UserRepository.LOCAL_ISSUER }.map { u ->
            val memberships = listOfNotNull(granted[u.id])
            val role = RoleResolver.userRoles(u, memberships, listOf(lib), access.defaultRole, access.groupRules)[lib.id]!!
            MemberView(u.id, u.label, u.email, granted[u.id]?.role, role, u.admin, u.disabled)
        }
    }

    @PutMapping("/{userId}")
    fun grant(@PathVariable libraryId: Long, @PathVariable userId: Long, @RequestBody body: MemberUpdate): MemberView {
        access.requireLibrary(libraryId, LibraryRole.ADMIN)
        val role = runCatching { LibraryRole.parse(body.role) }.getOrElse { badRequest(it.message ?: "bad role") }
        if (role == LibraryRole.NONE) badRequest("role must be viewer, curator or admin; DELETE the member to revoke")
        users.find(userId) ?: notFound("user $userId not found")
        users.grant(userId, libraryId, role, access.userId())
        access.invalidate(userId)
        return members(libraryId).first { it.userId == userId }
    }

    /** Revokes the granted role; returns what the user still has in the library (through a group, the default, ...). */
    @DeleteMapping("/{userId}")
    fun revoke(@PathVariable libraryId: Long, @PathVariable userId: Long): MemberView {
        access.requireLibrary(libraryId, LibraryRole.ADMIN)
        if (!users.revoke(userId, libraryId)) throw ApiException(HttpStatus.NOT_FOUND, "user $userId has no role granted in this library")
        access.invalidate(userId)
        return members(libraryId).first { it.userId == userId }
    }
}
