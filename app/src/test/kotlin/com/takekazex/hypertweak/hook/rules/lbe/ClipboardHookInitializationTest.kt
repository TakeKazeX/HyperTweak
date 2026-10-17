package com.takekazex.hypertweak.hook.rules.lbe

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClipboardHookInitializationTest {
    @Test fun `startup only queues work and duplicate preference retries do not scan twice`() {
        val main = ArrayDeque<() -> Unit>()
        val worker = ArrayDeque<() -> Unit>()
        val gate = ClipboardHookInitialization({ main.add(it) }, { worker.add(it) })
        var scans = 0
        var installed = 0
        repeat(2) { gate.schedule({ ++scans }, { installed = it }) }
        assertEquals(0, scans)
        assertEquals(1, main.size)
        main.removeFirst()()
        assertEquals(0, scans)
        assertEquals(0, installed)
        worker.removeFirst()()
        assertEquals(1, scans)
        assertEquals(1, installed)
    }

    @Test fun `retiring queued work prevents scanning and permits a new generation`() {
        val main = ArrayDeque<() -> Unit>()
        val worker = ArrayDeque<() -> Unit>()
        val gate = ClipboardHookInitialization({ main.add(it) }, { worker.add(it) })
        gate.schedule({ fail("retired scan"); 1 }, { fail("retired install") })
        gate.cancel()
        var installed = 0
        gate.schedule({ 2 }, { installed = it })
        while (main.isNotEmpty()) main.removeFirst()()
        assertEquals(1, worker.size)
        worker.removeFirst()()
        assertEquals(2, installed)
    }

    @Test fun `hot reload does not wait for a blocked native scan or publish its result`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var installed = false
        val gate = ClipboardHookInitialization({ it() }, { task ->
            Thread { try { task() } finally { finished.countDown() } }.start()
        })
        gate.schedule({ started.countDown(); release.await(); "old" }, { installed = true })
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val cancelled = CountDownLatch(1)
        Thread { gate.cancel(); cancelled.countDown() }.start()
        try {
            assertTrue("cancellation waited for scanning", cancelled.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertFalse(installed)
    }

    @Test fun `retired worker completion cannot clear the new pending generation`() {
        val main = ArrayDeque<() -> Unit>()
        val worker = ArrayDeque<() -> Unit>()
        val gate = ClipboardHookInitialization({ main.add(it) }, { worker.add(it) })
        gate.schedule({
            gate.cancel()
            gate.schedule({ "new" }, {})
            "old"
        }, { fail("retired install") })
        main.removeFirst()()
        worker.removeFirst()()
        gate.schedule({ fail("duplicate scan"); "duplicate" }, {})
        assertEquals(1, main.size)
        main.removeFirst()()
        worker.removeFirst()()
    }

    @Test fun `missing target keeps host untouched and allows retry`() {
        val gate = ClipboardHookInitialization({ it() }, { it() })
        gate.schedule<String>({ null }, { fail("unresolved target") })
        var installed = false
        gate.schedule({ "resolved" }, { installed = true })
        assertTrue(installed)
    }
}
