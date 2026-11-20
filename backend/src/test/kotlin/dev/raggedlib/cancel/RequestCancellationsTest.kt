package dev.raggedlib.cancel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
