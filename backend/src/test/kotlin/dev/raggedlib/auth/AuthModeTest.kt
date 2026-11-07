package dev.raggedlib.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthModeTest {
    @Test
    fun `parses the two modes, in any case`() {
        assertEquals(AuthMode.OIDC, AuthMode.parse("oidc"))
        assertEquals(AuthMode.SINGLE_USER, AuthMode.parse(" Single-User "))
    }

    @Test
    fun `anything else is a startup error, not a silent default`() {
        for (bad in listOf("", "none", "single_user", "off")) {
            assertFailsWith<IllegalArgumentException>(bad) { AuthMode.parse(bad) }
        }
    }
}
