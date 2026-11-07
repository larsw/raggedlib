package dev.raggedlib.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The admin-claim rules of onto-workbench's ADR 0003, ported. */
class ClaimsTest {
    @Test
    fun `a string claim must equal one of the values`() {
        assertTrue(Claims.matches(mapOf("role" to "admin"), "role", listOf("admin")))
        assertTrue(Claims.matches(mapOf("role" to "ops"), "role", listOf("admin", " ops ")))
        assertFalse(Claims.matches(mapOf("role" to "Admin"), "role", listOf("admin")), "case-sensitive")
        assertFalse(Claims.matches(mapOf("role" to "administrators-x"), "role", listOf("administrators")))
    }

    @Test
    fun `a list claim must contain one of the values`() {
        assertTrue(Claims.matches(mapOf("groups" to listOf("users", "administrators")), "groups", listOf("administrators")))
        assertTrue(Claims.matches(mapOf("groups" to arrayOf("administrators")), "groups", listOf("administrators")))
        assertFalse(Claims.matches(mapOf("groups" to listOf("users", 3)), "groups", listOf("administrators")))
    }

    @Test
    fun `a missing or odd claim, or no values, is never admin`() {
        assertFalse(Claims.matches(null, "groups", listOf("a")))
        assertFalse(Claims.matches(mapOf("groups" to listOf("a")), "", listOf("a")))
        assertFalse(Claims.matches(mapOf("groups" to listOf("a")), "groups", emptyList()))
        assertFalse(Claims.matches(mapOf("groups" to listOf("a")), "groups", listOf(" ")))
        assertFalse(Claims.matches(mapOf("other" to "a"), "groups", listOf("a")))
        assertFalse(Claims.matches(mapOf("groups" to 1), "groups", listOf("1")))
        assertFalse(Claims.matches(mapOf("groups" to mapOf("a" to true)), "groups", listOf("a")))
    }

    @Test
    fun `groups come from a list or a comma-separated string`() {
        assertEquals(listOf("a", "b"), Claims.strings(mapOf("groups" to listOf("a", " b ", "", "a")), "groups"))
        assertEquals(listOf("a", "b"), Claims.strings(mapOf("groups" to "a, b"), "groups"))
        assertEquals(emptyList(), Claims.strings(mapOf("groups" to 5), "groups"))
        assertEquals(emptyList(), Claims.strings(null, "groups"))
    }
}
