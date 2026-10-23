package com.takekazex.hypertweak.hook.rules.system

import org.junit.Assert.*
import org.junit.Test

class PowerSaveExitRequestTest {
    @Test fun `provider failure retains edge through duplicate events and stops after dispatch`() {
        var attempts = 0
        val delivered = mutableListOf<Int>()
        val request = PowerSaveExitRequest({ user ->
            if (attempts++ == 0) throw IllegalArgumentException("Unknown authority")
            delivered += user
        }) { _, _, _ -> }
        request.submit(0)
        assertEquals(250L, request.step(0))
        // A queued battery broadcast may arrive before the scheduled retry.
        assertEquals(240L, request.step(10))
        assertTrue(request.pending)
        assertNull(request.step(250))
        assertFalse(request.pending)
        // Neither a later observer nor a manual re-enable can replay the successful command.
        repeat(10) { assertNull(request.step(1_000L + it)) }
        assertEquals(listOf(0), delivered)
        assertEquals(2, attempts)
    }

    @Test fun `persistent provider failure has a finite budget`() {
        val exhausted = mutableListOf<Boolean>()
        val request = PowerSaveExitRequest({ throw IllegalStateException("not ready") }) { _, _, last -> exhausted += last }
        request.submit(0)
        assertEquals(250L, request.step(0))
        assertEquals(1_000L, request.step(250))
        assertNull(request.step(1_250))
        repeat(20) { assertNull(request.step(5_000L + it)) }
        assertEquals(listOf(false, false, true), exhausted)
        assertFalse(request.pending)
    }

    @Test fun `disconnect disable and user replacement cancel stale exit`() {
        val delivered = mutableListOf<Int>()
        var ready = false
        val request = PowerSaveExitRequest({ user ->
            check(ready)
            delivered += user
        }) { _, _, _ -> }
        request.submit(0)
        request.step(0)
        request.cancel()
        ready = true
        assertNull(request.step(250))
        assertTrue(delivered.isEmpty())
        request.submit(10)
        assertNull(request.step(300))
        assertEquals(listOf(10), delivered)
    }
}
