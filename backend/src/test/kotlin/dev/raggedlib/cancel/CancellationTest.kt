package dev.raggedlib.cancel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CancellationTest {
    @Test
    fun `cancelling runs each callback still registered, once`() {
        val c = Cancellation()
        val ran = mutableListOf<String>()
        c.onCancel { ran += "a" }
        c.onCancel { ran += "closed" }.close()
        c.onCancel { error("boom") } // doesn't keep the others from running
        c.onCancel { ran += "b" }
        assertFalse(c.isCancelled)
        c.throwIfCancelled()

        c.cancel()
        c.cancel()
        assertTrue(c.isCancelled)
        assertEquals(listOf("a", "b"), ran)
        assertFailsWith<RequestCancelledException> { c.throwIfCancelled() }
    }

    @Test
    fun `a callback registered once cancelled runs right away`() {
        val c = Cancellation().apply { cancel() }
        var ran = false
        c.onCancel { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `the current one is the one bound, NONE elsewhere, which can't be cancelled`() {
        assertSame(Cancellation.NONE, Cancellation.current())
        val c = Cancellation()
        assertSame(c, c.bind { Cancellation.current() })
        assertSame(Cancellation.NONE, Cancellation.current())
        assertFailsWith<IllegalStateException> { Cancellation.NONE.cancel() }
        assertFalse(Cancellation.NONE.isCancelled)
    }
}
