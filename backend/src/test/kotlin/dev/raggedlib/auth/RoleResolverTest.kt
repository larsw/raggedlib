package dev.raggedlib.auth

import dev.raggedlib.auth.LibraryRole.ADMIN
import dev.raggedlib.auth.LibraryRole.CURATOR
import dev.raggedlib.auth.LibraryRole.NONE
import dev.raggedlib.auth.LibraryRole.VIEWER
import dev.raggedlib.library.LibraryInfo
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoleResolverTest {
    private val fiction = LibraryInfo(1, "Fiction", Path.of("/l/1"))
    private val science = LibraryInfo(2, "Science", Path.of("/l/2"))
    private val libs = listOf(fiction, science)

    private fun user(groups: List<String> = emptyList(), admin: Boolean = false, granted: Boolean = false, disabled: Boolean = false) =
        UserRow(7, "iss", "sub", "u@x", "U", groups, admin, granted, disabled, Instant.EPOCH, null)

    private fun member(lib: Long, role: LibraryRole) = Membership(7, lib, role, Instant.EPOCH)

    @Test
    fun `without grants, groups or a default a user has no library`() {
        assertEquals(mapOf(1L to NONE, 2L to NONE), RoleResolver.userRoles(user(), emptyList(), libs, NONE, emptyList()))
    }

    @Test
    fun `the highest of default, grant and groups wins, per library`() {
        val rules = listOf(GroupRule("readers", VIEWER, "*"), GroupRule("sci-curators", CURATOR, "science"), GroupRule("x", ADMIN, "1"))
        val roles = RoleResolver.userRoles(
            user(groups = listOf("readers", "sci-curators")), listOf(member(1, CURATOR)), libs, NONE, rules,
        )
        assertEquals(mapOf(1L to CURATOR, 2L to CURATOR), roles)
        // a group rule names a library by (case-insensitive) name or id
        assertEquals(ADMIN, RoleResolver.userRoles(user(groups = listOf("x")), emptyList(), libs, NONE, rules)[1])
        assertEquals(NONE, RoleResolver.userRoles(user(groups = listOf("x")), emptyList(), libs, NONE, rules)[2])
        // a grant below the default doesn't lower it
        assertEquals(CURATOR, RoleResolver.userRoles(user(), listOf(member(2, VIEWER)), libs, CURATOR, emptyList())[2])
    }

    @Test
    fun `a global admin is admin everywhere, a disabled user nowhere`() {
        assertEquals(mapOf(1L to ADMIN, 2L to ADMIN), RoleResolver.userRoles(user(admin = true), emptyList(), libs, NONE, emptyList()))
        assertEquals(mapOf(1L to ADMIN, 2L to ADMIN), RoleResolver.userRoles(user(granted = true), emptyList(), libs, NONE, emptyList()))
        assertEquals(
            mapOf(1L to NONE, 2L to NONE),
            RoleResolver.userRoles(user(admin = true, disabled = true), listOf(member(1, ADMIN)), libs, VIEWER, emptyList()),
        )
    }

    @Test
    fun `a system key views, or curates with library curate, and never administers`() {
        assertEquals(mapOf(1L to VIEWER, 2L to VIEWER), RoleResolver.systemRoles(setOf(ApiScope.LIBRARY_READ), libs))
        assertEquals(mapOf(1L to CURATOR, 2L to CURATOR), RoleResolver.systemRoles(setOf(ApiScope.LIBRARY_READ, ApiScope.LIBRARY_CURATE), libs))
    }

    @Test
    fun `a key restricted to some libraries has no role in the others`() {
        val roles = mapOf(1L to ADMIN, 2L to VIEWER)
        assertEquals(roles, RoleResolver.restrict(roles, null))
        assertEquals(mapOf(1L to NONE, 2L to VIEWER), RoleResolver.restrict(roles, setOf(2L)))
    }

    @Test
    fun `roles and scopes parse leniently and order as documented`() {
        assertEquals(CURATOR, LibraryRole.parse(" Curator "))
        assertFailsWith<IllegalArgumentException> { LibraryRole.parse("owner") }
        assertTrue(ADMIN.atLeast(CURATOR) && CURATOR.atLeast(VIEWER) && VIEWER.atLeast(VIEWER))
        assertFalse(VIEWER.atLeast(CURATOR))
        assertEquals(ApiScope.FAVORITES_WRITE, ApiScope.parse("favorites:write"))
        assertFailsWith<IllegalArgumentException> { ApiScope.parse("favorites:*") }
        assertEquals(setOf(ApiScope.LIBRARY_READ, ApiScope.LIBRARY_CURATE), ApiScope.SYSTEM)
    }
}
