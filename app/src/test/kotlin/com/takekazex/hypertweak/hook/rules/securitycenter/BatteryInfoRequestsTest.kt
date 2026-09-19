package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.util.BatteryInfoChannel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class BatteryInfoRequestsTest {
    private class Harness {
        var now = 1_000L
        val jobs = ArrayDeque<Runnable>()
        var collections = 0
        var duringCollect: ((() -> Boolean) -> Unit)? = null
        val failures = mutableListOf<Throwable>()
        val requests = BatteryInfoRequests({ now }, Executor(jobs::addLast), { active ->
            collections++
            duringCollect?.invoke(active)
        }, failures::add)
        fun run() { while (jobs.isNotEmpty()) jobs.removeFirst().run() }
    }

    @Test fun `construction and elapsed time never schedule a read`() {
        val h = Harness()
        h.now += 100_000
        h.run()
        assertEquals(0, h.collections)
        assertTrue(h.jobs.isEmpty())
    }

    @Test fun `a burst queues one job and collects only the latest request`() {
        val h = Harness()
        repeat(100) { h.requests.request("page", h.now++) }
        assertEquals(1, h.jobs.size)
        h.run()
        assertEquals(1, h.collections)
        h.now += 60_000
        h.run()
        assertEquals(1, h.collections)
    }

    @Test fun `expired future and invalid requests cannot read hardware`() {
        val h = Harness()
        h.requests.request("page", h.now + 1)
        h.requests.request("", h.now)
        h.requests.request("x".repeat(65), h.now)
        h.requests.request("page", -1)
        assertTrue(h.jobs.isEmpty())
        h.requests.request("page", h.now)
        h.now += BatteryInfoChannel.REQUEST_LIFETIME_MS
        h.run()
        assertEquals(0, h.collections)
    }

    @Test fun `stop cancels a queued read and old messages cannot revive it`() {
        val h = Harness()
        h.requests.request("page", h.now)
        h.requests.stop("page", ++h.now)
        h.requests.request("page", ++h.now)
        h.run()
        assertEquals(0, h.collections)
    }

    @Test fun `stop during a read invalidates the continuation`() {
        val h = Harness()
        h.duringCollect = { active ->
            assertTrue(active())
            h.requests.stop("page", ++h.now)
            assertFalse(active())
        }
        h.requests.request("page", h.now)
        h.run()
        assertEquals(1, h.collections)
    }

    @Test fun `expiry during a read invalidates the continuation without a stop message`() {
        val h = Harness()
        h.duringCollect = { active ->
            h.now += BatteryInfoChannel.REQUEST_LIFETIME_MS
            assertFalse(active())
        }
        h.requests.request("page", h.now)
        h.run()
        assertEquals(1, h.collections)
    }

    @Test fun `requests during a read are conflated and never execute concurrently`() {
        val h = Harness()
        var entered = false
        h.duringCollect = { active ->
            assertFalse(entered)
            entered = true
            if (h.collections == 1) {
                h.now += 1_000
                repeat(100) { h.requests.request("page", h.now++) }
                assertTrue(active())
                assertTrue(h.jobs.isEmpty())
            }
            entered = false
        }
        h.requests.request("page", h.now)
        h.run()
        assertEquals(2, h.collections)
    }

    @Test fun `a new session invalidates an old read but an old stop cannot cancel the new one`() {
        val h = Harness()
        h.duringCollect = { active ->
            if (h.collections == 1) {
                h.now += 1_000
                h.requests.request("new", h.now)
                assertFalse(active())
                h.requests.stop("old", ++h.now)
            } else {
                assertTrue(active())
            }
        }
        h.requests.request("old", h.now)
        h.run()
        assertEquals(2, h.collections)
    }

    @Test fun `manual refresh cannot bypass the start rate limit`() {
        val h = Harness()
        h.requests.request("page", h.now)
        h.run()
        h.requests.request("page", ++h.now)
        h.run()
        assertEquals(1, h.collections)
        h.now += 1_000
        h.requests.request("page", h.now)
        h.run()
        assertEquals(2, h.collections)
    }

    @Test fun `hot reload closes pending and in flight requests permanently`() {
        val h = Harness()
        h.duringCollect = { active ->
            h.requests.request("page", ++h.now)
            h.requests.close()
            assertFalse(active())
        }
        h.requests.request("page", h.now)
        h.run()
        h.requests.request("new", ++h.now)
        h.run()
        assertEquals(1, h.collections)
        assertTrue(h.jobs.isEmpty())
    }

    @Test fun `collection failure does not wedge the next request`() {
        val h = Harness()
        h.duringCollect = { throw IllegalStateException("provider temporarily unavailable") }
        h.requests.request("page", h.now)
        h.run()
        assertEquals(1, h.failures.size)
        h.now += 5_000
        h.duringCollect = null
        h.requests.request("page", h.now)
        h.run()
        assertEquals(2, h.collections)
    }
}
