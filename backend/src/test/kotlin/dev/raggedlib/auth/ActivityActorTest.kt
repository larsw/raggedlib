package dev.raggedlib.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityActorTest {
    @Test
    fun `sessions are recorded as the user's display name`() {
        assertEquals("Bob", formatActivityActor(caller(userId = 3), "Bob"))
    }

    @Test
    fun `personal keys include the user and key name`() {
        assertEquals("Bob, via key 'tagger'", formatActivityActor(caller(userId = 3, viaApiKey = true), "Bob"))
    }

    @Test
    fun `system keys are identified explicitly`() {
        assertEquals("system key 'indexer'", formatActivityActor(caller(userId = null, viaApiKey = true, keyName = "indexer"), null))
    }

    private fun caller(userId: Long?, viaApiKey: Boolean = false, keyName: String = "tagger") = Caller(
        userId = userId,
        scopes = emptySet(),
        keyLibraries = null,
        viaApiKey = viaApiKey,
        label = "key '$keyName'",
    )
}
