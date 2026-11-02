package dev.raggedlib.ingest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdjustableLimitTest {
    @Test
    fun `admits up to the limit, more after it is raised`() {
        val max = AtomicInteger(2)
        val limit = AdjustableLimit { max.get() }
        limit.acquire()
        limit.acquire()
        val third = CountDownLatch(1)
        Thread.ofVirtual().start { limit.acquire(); third.countDown() }
        assertFalse(third.await(200, TimeUnit.MILLISECONDS)) // full
        max.set(3)
        limit.refresh()
        assertTrue(third.await(1, TimeUnit.SECONDS))
        assertEquals(3, limit.used)
    }

    @Test
    fun `after lowering, nobody new gets in until holders are below the new limit`() {
        val max = AtomicInteger(3)
        val limit = AdjustableLimit { max.get() }
        repeat(3) { limit.acquire() }
        max.set(1)
        val next = CountDownLatch(1)
        Thread.ofVirtual().start { limit.acquire(); next.countDown() }
        limit.release() // 2 in use, limit 1: still full
        assertFalse(next.await(200, TimeUnit.MILLISECONDS))
        limit.release() // 1 in use
        assertFalse(next.await(200, TimeUnit.MILLISECONDS))
        limit.release() // 0 in use
        assertTrue(next.await(1, TimeUnit.SECONDS))
    }
}
