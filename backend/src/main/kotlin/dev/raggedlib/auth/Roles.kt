package dev.raggedlib.auth

import com.fasterxml.jackson.annotation.JsonValue

/**
 * A user's role in one library, from least to most. Each includes the ones below it:
 * - VIEWER: browse, search, ask and read the library's books; review them and put them on lists.
 * - CURATOR: also sync the library, index and re-index its books, and remove other users' reviews of them.
 * - ADMIN: also grant and revoke roles in the library.
 *
 * A global admin is ADMIN in every library. NONE (no access) is never stored, only computed.
 */
enum class LibraryRole {
    NONE, VIEWER, CURATOR, ADMIN;

    fun atLeast(other: LibraryRole) = ordinal >= other.ordinal

    companion object {
        fun parse(s: String): LibraryRole =
            entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("unknown role '$s'; use one of ${entries.joinToString()}")

        fun max(a: LibraryRole, b: LibraryRole) = if (a.ordinal >= b.ordinal) a else b
    }
}

/**
 * What an API key may do. A key never grants more than its owner has: a scope only allows an action in the
 * libraries where the owner's role allows it too. A browser session has every scope.
 *
 * [perUser] scopes act on the owner's own data (favorite lists, reviews), so system-wide keys can't have them.
 */
enum class ApiScope(@get:JsonValue val id: String, val description: String, val perUser: Boolean) {
    LIBRARY_READ("library:read", "Search, ask and browse the books of the libraries you can view", false),
    LIBRARY_CURATE("library:curate", "Sync libraries and (re-)index books, in the libraries you curate", false),
    FAVORITES_READ("favorites:read", "Read your favorite lists, and the lists others share", true),
    FAVORITES_WRITE("favorites:write", "Create, change and delete your favorite lists", true),
    REVIEWS_READ("reviews:read", "Read book reviews", true),
    REVIEWS_WRITE("reviews:write", "Write, change and delete your book reviews", true),
    ;

    /** Spring Security authority, in the `SCOPE_` style of OAuth2 scopes. */
    val authority: String get() = "SCOPE_$id"

    companion object {
        fun parse(s: String): ApiScope =
            entries.firstOrNull { it.id == s.trim() }
                ?: throw IllegalArgumentException("unknown scope '$s'; use one of ${entries.joinToString { it.id }}")

        val SYSTEM: Set<ApiScope> = entries.filterNot { it.perUser }.toSet()
    }
}

/** Spring Security authorities besides the scopes. */
object Authorities {
    const val USER = "ROLE_USER"
    const val ADMIN = "ROLE_ADMIN"
    /** The request is authenticated by a browser session (or single-user mode), not an API key. */
    const val SESSION = "AUTH_SESSION"
    /** The request is authenticated by an API key. */
    const val API_KEY = "AUTH_API_KEY"
}
