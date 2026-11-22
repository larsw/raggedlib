package dev.raggedlib.cancel

import dev.raggedlib.auth.ApiScope
import dev.raggedlib.auth.Caller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RequestCancellationsTest {
    private var now = 1_000_000L
    private val cancellations = RequestCancellations().also { it.clock = { now } }

    private fun cancelled() = Cancellation.current().isCancelled

    @Test
    fun `a request is cancelled by its id, by its own caller only`() {
        cancellations.handle("alice", "r1") {
            assertFalse(cancellations.cancel("bob", "r1"))
            assertFalse(cancellations.cancel("alice", "r2"))
            assertFalse(cancelled())
            assertTrue(cancellations.cancel("alice", "r1"))
            assertTrue(cancelled())
        }
        assertEquals(0, cancellations.runningCount())
        // bob's cancel waits for a request of his own with that id, not alice's
        assertFalse(cancellations.handle("alice", "r1") { cancelled() })
        assertTrue(cancellations.handle("bob", "r1") { cancelled() })
    }

    @Test
    fun `a cancel that comes before its request cancels it when it arrives, once, for a minute`() {
        assertFalse(cancellations.cancel("alice", "early"))
        assertTrue(cancellations.handle("alice", "early") { cancelled() })
        assertFalse(cancellations.handle("alice", "early") { cancelled() }, "used up")

        cancellations.cancel("alice", "late")
        now += 61_000
        assertFalse(cancellations.handle("alice", "late") { cancelled() }, "expired")
    }

    @Test
    fun `requests are not cancellable after they end`() {
        assertEquals("done", cancellations.handle("alice", "r1") { "done" })
        assertEquals(0, cancellations.runningCount())
        assertFalse(cancellations.cancel("alice", "r1"))
    }

    @Test
    fun `API keys are told apart by their id, not their name`() {
        fun key(id: String, userId: Long? = null) =
            Caller(userId, setOf(ApiScope.LIBRARY_READ), null, viaApiKey = true, label = "key 'claude'", apiKeyId = id)
        fun session(userId: Long, label: String) = Caller(userId, ApiScope.entries.toSet(), null, viaApiKey = false, label = label)

        val of = RequestCancellations::callerKey
        assertNotEquals(of(key("config-1")), of(key("0b6f3c1e-2f0a-4d5e-9c1b-7a8d9e0f1a2b")))
        assertNotEquals(of(key("k1", userId = 7)), of(key("k2", userId = 7)))
        assertNotEquals(of(key("k1", userId = 7)), of(session(7, "user #7")), "a personal key is not its owner's session")
        assertEquals(of(session(7, "user #7")), of(session(7, "local user")))
        assertNotEquals(of(session(7, "user #7")), of(session(8, "user #8")))
    }
}
