package dev.raggedlib.auth

/** Reading OIDC claims whose shape varies between identity providers. */
object Claims {
    /**
     * Whether [claims] make a user a global admin (same rules as onto-workbench's ADR 0003): only the top-level claim
     * [claimName] counts; a string claim must equal one of [acceptable], a list claim must contain one; comparison is
     * case-sensitive; a missing or otherwise-typed claim, or no acceptable values, means not admin.
     */
    fun matches(claims: Map<String, Any?>?, claimName: String, acceptable: Collection<String>): Boolean {
        val accept = acceptable.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (claims == null || claimName.isBlank() || accept.isEmpty()) return false
        return when (val value = claims[claimName]) {
            is String -> value in accept
            is Collection<*> -> value.any { it is String && it in accept }
            is Array<*> -> value.any { it is String && it in accept }
            else -> false
        }
    }

    /** The strings in claim [name]: a list of strings, or a single (comma-separated) string; else none. */
    fun strings(claims: Map<String, Any?>?, name: String): List<String> {
        if (claims == null || name.isBlank()) return emptyList()
        return when (val value = claims[name]) {
            is String -> value.split(',')
            is Collection<*> -> value.filterIsInstance<String>()
            is Array<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
