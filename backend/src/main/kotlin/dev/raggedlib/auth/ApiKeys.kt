package dev.raggedlib.auth

import dev.raggedlib.config.RaggedlibProperties
import dev.raggedlib.config.badRequest
import dev.raggedlib.config.notFound
import dev.raggedlib.library.LibraryRegistry
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Who an authenticated API key acts as. */
data class ApiKeyPrincipal(
    /** null for a key from the configuration. */
    val keyId: UUID?,
    val name: String,
    /** The owner of a personal key; null for a system-wide key. */
    val userId: Long?,
    val scopes: Set<ApiScope>,
    /** The key only reaches these libraries; null = no restriction of its own. */
    val libraryIds: Set<Long>?,
)

/** An API key as shown to its owner or an admin; never the key itself. */
data class ApiKeyView(
    val id: UUID,
    val name: String,
    /** The key's first characters, to tell keys apart. */
    val keyPrefix: String,
    val scopes: List<ApiScope>,
    val libraryIds: List<Long>?,
    /** True for a system-wide key (no owner). */
    val system: Boolean,
    val createdBy: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant?,
)

/** The response to creating a key: the only time [rawKey] is shown. */
data class CreatedApiKey(val apiKey: ApiKeyView, val rawKey: String)

/** POST body for a new key. [expiresAt] null = never expires. */
data class ApiKeyRequest(
    val name: String = "",
    val scopes: List<String> = emptyList(),
    val libraryIds: List<Long>? = null,
    val expiresAt: Instant? = null,
)

/**
 * API keys: personal ones, minted by users for themselves with a choice of scopes, and system-wide ones that admins
 * create, which act as no user. Keys look like `rlk_<43 base64url chars>`; only their SHA-256 is stored.
 */
@Service
class ApiKeyService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val users: UserRepository,
    private val libraries: LibraryRegistry,
    props: RaggedlibProperties,
) {
    private val static = StaticApiKeys(props.mcp.apiKeys)
    private val random = SecureRandom()

    private data class Row(
        val id: UUID, val userId: Long?, val name: String, val keyPrefix: String, val scopes: List<String>,
        val libraryIds: List<Long>?, val createdBy: String?, val createdAt: Instant, val lastUsedAt: Instant?,
        val expiresAt: Instant?,
    )

    private val rowMapper = RowMapper { rs, _ ->
        Row(
            id = rs.getObject("id", UUID::class.java),
            userId = rs.getObject("user_id") as Long?,
            name = rs.getString("name"),
            keyPrefix = rs.getString("key_prefix"),
            scopes = rs.strings("scopes"),
            libraryIds = rs.longs("library_ids"),
            createdBy = rs.getString("created_by_label"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
            expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
        )
    }

    private val select = """
        select k.id, k.user_id, k.name, k.key_prefix, k.scopes, k.library_ids, k.created_at, k.last_used_at, k.expires_at,
               coalesce(c.display_name, c.email, c.subject) as created_by_label
        from api_keys k left join users c on c.id = k.created_by
    """.trimIndent()

    /** The principal for [raw], or null if it isn't a valid key (unknown, revoked, expired, or its owner disabled). */
    fun authenticate(raw: String): ApiKeyPrincipal? {
        static.match(raw)?.let { return ApiKeyPrincipal(null, it, null, setOf(ApiScope.LIBRARY_READ), null) }
        if (!raw.startsWith(PREFIX)) return null
        val row = jdbc.query(
            "$select where k.key_hash = :hash and k.revoked_at is null", mapOf("hash" to hash(raw)), rowMapper,
        ).firstOrNull() ?: return null
        val now = Instant.now()
        if (row.expiresAt != null && !row.expiresAt.isAfter(now)) return null
        if (row.userId != null && users.find(row.userId)?.disabled != false) return null
        if (row.lastUsedAt == null || Duration.between(row.lastUsedAt, now) > LAST_USED_RESOLUTION) {
            jdbc.update("update api_keys set last_used_at = now() where id = :id", mapOf("id" to row.id))
        }
        val scopes = row.scopes.mapNotNull { s -> ApiScope.entries.firstOrNull { it.id == s } }.toSet()
        return ApiKeyPrincipal(row.id, row.name, row.userId, scopes, row.libraryIds?.toSet())
    }

    /** [userId]'s keys that are still usable or merely expired (revoked ones are gone). */
    fun personalKeys(userId: Long): List<ApiKeyView> = jdbc.query(
        "$select where k.user_id = :userId and k.revoked_at is null order by k.created_at desc", mapOf("userId" to userId), rowMapper,
    ).map(::view)

    fun systemKeys(): List<ApiKeyView> = jdbc.query(
        "$select where k.user_id is null and k.revoked_at is null order by k.created_at desc", rowMapper,
    ).map(::view)

    fun createPersonal(userId: Long, req: ApiKeyRequest): CreatedApiKey = create(userId, userId, req, ApiScope.entries.toSet())

    /** A system-wide key: library scopes only, since it acts as no user. */
    fun createSystem(adminId: Long, req: ApiKeyRequest): CreatedApiKey = create(null, adminId, req, ApiScope.SYSTEM)

    fun revokePersonal(userId: Long, keyId: UUID) {
        val n = jdbc.update(
            "update api_keys set revoked_at = now() where id = :id and user_id = :userId and revoked_at is null",
            mapOf("id" to keyId, "userId" to userId),
        )
        if (n == 0) notFound("API key $keyId not found")
    }

    fun revokeSystem(keyId: UUID) {
        val n = jdbc.update(
            "update api_keys set revoked_at = now() where id = :id and user_id is null and revoked_at is null", mapOf("id" to keyId),
        )
        if (n == 0) notFound("API key $keyId not found")
    }

    private fun create(ownerId: Long?, createdBy: Long, req: ApiKeyRequest, allowed: Set<ApiScope>): CreatedApiKey {
        val name = req.name.trim()
        if (name.isEmpty()) badRequest("name must not be empty")
        if (name.length > 100) badRequest("name must be at most 100 characters")
        val scopes = req.scopes.map { s -> ApiScope.parse(s) }.toSet()
        if (scopes.isEmpty()) badRequest("select at least one scope")
        (scopes - allowed).takeIf { it.isNotEmpty() }?.let { bad ->
            badRequest("a system-wide key can't have per-user scopes: ${bad.joinToString { it.id }}")
        }
        val libraryIds = req.libraryIds?.distinct()
        if (libraryIds != null) {
            if (libraryIds.isEmpty()) badRequest("libraryIds must not be empty; leave it out for every library")
            val known = libraries.all.map { it.id }.toSet()
            (libraryIds - known).takeIf { it.isNotEmpty() }?.let { badRequest("unknown libraries: $it") }
        }
        if (req.expiresAt != null && !req.expiresAt.isAfter(Instant.now())) badRequest("expiresAt must be in the future")

        val raw = generate()
        val id = jdbc.queryForObject(
            """
            insert into api_keys (user_id, name, key_hash, key_prefix, scopes, library_ids, created_by, expires_at)
            values (:userId, :name, :hash, :prefix, :scopes, :libraryIds, :createdBy, :expiresAt)
            returning id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("userId", ownerId)
                .addValue("name", name)
                .addValue("hash", hash(raw))
                .addValue("prefix", raw.take(DISPLAY_PREFIX))
                .addValue("scopes", ApiScope.entries.filter { it in scopes }.map { it.id }.toTypedArray())
                .addValue("libraryIds", libraryIds?.toTypedArray())
                .addValue("createdBy", createdBy)
                .addValue("expiresAt", req.expiresAt?.let(Timestamp::from)),
            UUID::class.java,
        )!!
        val row = jdbc.query("$select where k.id = :id", mapOf("id" to id), rowMapper).single()
        return CreatedApiKey(view(row), raw)
    }

    private fun view(r: Row) = ApiKeyView(
        id = r.id,
        name = r.name,
        keyPrefix = r.keyPrefix,
        scopes = r.scopes.mapNotNull { s -> ApiScope.entries.firstOrNull { it.id == s } },
        libraryIds = r.libraryIds,
        system = r.userId == null,
        createdBy = r.createdBy,
        createdAt = r.createdAt,
        lastUsedAt = r.lastUsedAt,
        expiresAt = r.expiresAt,
    )

    /** A new key: the prefix and 32 random bytes, base64url. */
    fun generate(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val PREFIX = "rlk_"
        private const val DISPLAY_PREFIX = 8
        /** last_used_at is updated at most this often per key. */
        private val LAST_USED_RESOLUTION: Duration = Duration.ofMinutes(1)

        fun hash(raw: String): String = HexFormat.of().formatHex(StaticApiKeys.sha256(raw))
    }
}
