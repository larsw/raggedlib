package dev.raggedlib.auth

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

/** A user as stored: an OIDC identity (or the single-user mode's local user). */
data class UserRow(
    val id: Long,
    val issuer: String,
    val subject: String,
    val email: String?,
    val displayName: String?,
    val groups: List<String>,
    val adminFromClaim: Boolean,
    val adminGranted: Boolean,
    val disabled: Boolean,
    val createdAt: Instant,
    val lastLoginAt: Instant?,
) {
    val admin: Boolean get() = adminFromClaim || adminGranted

    /** What to call the user: display name, else email, else the subject. */
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: email?.takeIf { it.isNotBlank() } ?: subject
}

/** A role granted in the app (not through a group). */
data class Membership(val userId: Long, val libraryId: Long, val role: LibraryRole, val grantedAt: Instant)

@Repository
class UserRepository(private val jdbc: NamedParameterJdbcTemplate) {
    private val columns = "id, issuer, subject, email, display_name, groups, admin_from_claim, admin_granted, disabled, created_at, last_login_at"

    private val mapper = RowMapper { rs, _ ->
        UserRow(
            id = rs.getLong("id"),
            issuer = rs.getString("issuer"),
            subject = rs.getString("subject"),
            email = rs.getString("email"),
            displayName = rs.getString("display_name"),
            groups = rs.strings("groups"),
            adminFromClaim = rs.getBoolean("admin_from_claim"),
            adminGranted = rs.getBoolean("admin_granted"),
            disabled = rs.getBoolean("disabled"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            lastLoginAt = rs.getTimestamp("last_login_at")?.toInstant(),
        )
    }

    fun find(id: Long): UserRow? =
        jdbc.query("select $columns from users where id = :id", mapOf("id" to id), mapper).firstOrNull()

    fun findAll(): List<UserRow> =
        jdbc.query("select $columns from users order by lower(coalesce(display_name, email, subject)), id", mapper)

    /**
     * Creates or refreshes the user signing in as ([issuer], [subject]): profile, groups and the admin claim are
     * taken as the identity provider has them now. Granted roles and the in-app admin flag stay.
     */
    fun upsertFromLogin(
        issuer: String, subject: String, email: String?, displayName: String?, groups: List<String>, adminFromClaim: Boolean,
    ): UserRow = jdbc.queryForObject(
        """
        insert into users (issuer, subject, email, display_name, groups, admin_from_claim, last_login_at)
        values (:issuer, :subject, :email, :displayName, :groups, :admin, now())
        on conflict (issuer, subject) do update set email = excluded.email, display_name = excluded.display_name,
            groups = excluded.groups, admin_from_claim = excluded.admin_from_claim, last_login_at = now()
        returning $columns
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("issuer", issuer)
            .addValue("subject", subject)
            .addValue("email", email)
            .addValue("displayName", displayName)
            .addValue("groups", groups.toTypedArray())
            .addValue("admin", adminFromClaim),
        mapper,
    )!!

    /** The single-user mode's built-in user (an admin), created on first use. */
    fun localUser(): UserRow = jdbc.queryForObject(
        """
        insert into users (issuer, subject, display_name, admin_granted, last_login_at)
        values (:issuer, 'local', 'Local user', true, now())
        on conflict (issuer, subject) do update set admin_granted = true
        returning $columns
        """.trimIndent(),
        mapOf("issuer" to LOCAL_ISSUER),
        mapper,
    )!!

    fun setAdminGranted(id: Long, admin: Boolean): Boolean =
        jdbc.update("update users set admin_granted = :admin where id = :id", mapOf("id" to id, "admin" to admin)) > 0

    fun setDisabled(id: Long, disabled: Boolean): Boolean =
        jdbc.update("update users set disabled = :disabled where id = :id", mapOf("id" to id, "disabled" to disabled)) > 0

    fun memberships(userId: Long): List<Membership> = jdbc.query(
        "select user_id, library_id, role, granted_at from library_members where user_id = :userId",
        mapOf("userId" to userId), membershipMapper,
    )

    fun allMemberships(): List<Membership> =
        jdbc.query("select user_id, library_id, role, granted_at from library_members", membershipMapper)

    fun membersOf(libraryId: Long): List<Membership> = jdbc.query(
        "select user_id, library_id, role, granted_at from library_members where library_id = :libraryId",
        mapOf("libraryId" to libraryId), membershipMapper,
    )

    fun grant(userId: Long, libraryId: Long, role: LibraryRole, grantedBy: Long?) {
        require(role != LibraryRole.NONE) { "NONE is not a role to grant; revoke instead" }
        jdbc.update(
            """
            insert into library_members (user_id, library_id, role, granted_by) values (:userId, :libraryId, :role, :by)
            on conflict (user_id, library_id) do update set role = excluded.role, granted_by = excluded.granted_by,
                granted_at = now()
            """.trimIndent(),
            mapOf("userId" to userId, "libraryId" to libraryId, "role" to role.name, "by" to grantedBy),
        )
    }

    fun revoke(userId: Long, libraryId: Long): Boolean = jdbc.update(
        "delete from library_members where user_id = :userId and library_id = :libraryId",
        mapOf("userId" to userId, "libraryId" to libraryId),
    ) > 0

    private val membershipMapper = RowMapper { rs, _ ->
        Membership(rs.getLong(1), rs.getLong(2), LibraryRole.valueOf(rs.getString(3)), rs.getTimestamp(4).toInstant())
    }

    companion object {
        const val LOCAL_ISSUER = "urn:raggedlib:local"
    }
}

internal fun ResultSet.strings(col: String): List<String> {
    val arr = getArray(col) ?: return emptyList()
    @Suppress("UNCHECKED_CAST")
    return (arr.array as Array<String>).toList()
}

internal fun ResultSet.longs(col: String): List<Long>? {
    val arr = getArray(col) ?: return null
    return (arr.array as Array<*>).map { (it as Number).toLong() }
}
