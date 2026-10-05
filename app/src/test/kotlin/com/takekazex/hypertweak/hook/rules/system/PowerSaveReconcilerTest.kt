package com.takekazex.hypertweak.hook.rules.system

import org.junit.Assert.*
import org.junit.Test

class PowerSaveReconcilerTest {
    private class Backend : PowerSaveReconciler.Backend {
        var state = PowerSaveReconciler.State(true, true)
        var readFailures = 0
        var nativeFailure = false
        var clears = 0
        val requests = mutableListOf<PowerSaveReconciler.Target>()
        override fun read(userId: Int): PowerSaveReconciler.State {
            if (readFailures-- > 0) error("provider not ready")
            return state
        }
        override fun requestNative(target: PowerSaveReconciler.Target) {
            if (nativeFailure) error("provider unavailable")
            requests += target
        }
        override fun clearFramework(): Boolean { clears++; return true }
    }
    private val off = PowerSaveReconciler.Target(0, false)
    private val on = PowerSaveReconciler.Target(0, true)
    private fun controller(backend: Backend) = PowerSaveReconciler(backend) { _, _ -> }

    @Test fun `100 percent plugged in dispatches native exit and confirms only after state changes`() {
        val backend = Backend()
        val c = controller(backend)
        assertEquals(false, BatteryAutoPowerSavePolicy.target(false, 20, true, true, 100, true))
        c.aim(off, force = true)
        assertTrue(c.step(0) is PowerSaveReconciler.Result.Waiting)
        assertEquals(listOf(off), backend.requests)
        assertEquals(1, backend.clears)
        // A successful Boolean return is not an acknowledgement: both observations still on.
        assertTrue(c.step(100) is PowerSaveReconciler.Result.Waiting)
        backend.state = PowerSaveReconciler.State(false, false)
        val confirmed = c.step(250) as PowerSaveReconciler.Result.Confirmed
        assertTrue(confirmed.changed)
        assertFalse(c.pending)
        assertFalse((c.step(500) as PowerSaveReconciler.Result.Confirmed).changed)
    }

    @Test fun `late native completion stays pending when only framework saver is off`() {
        val backend = Backend().apply { state = PowerSaveReconciler.State(true, false) }
        val c = controller(backend)
        c.aim(off)
        assertTrue(c.step(0) is PowerSaveReconciler.Result.Waiting)
        assertTrue(c.step(250) is PowerSaveReconciler.Result.Waiting)
        assertEquals(2, backend.requests.size)
        backend.state = PowerSaveReconciler.State(false, false)
        assertTrue(c.step(1250) is PowerSaveReconciler.Result.Confirmed)
    }

    @Test fun `vendor flag off but framework on replays native exit rather than claiming success`() {
        val backend = Backend().apply { state = PowerSaveReconciler.State(false, true) }
        val c = controller(backend)
        c.aim(off)
        assertTrue(c.step(0) is PowerSaveReconciler.Result.Waiting)
        assertEquals(listOf(off), backend.requests)
        assertEquals(1, backend.clears)
        backend.state = PowerSaveReconciler.State(false, false)
        assertTrue(c.step(250) is PowerSaveReconciler.Result.Confirmed)
    }

    @Test fun `accepted requests with no real change exhaust a finite budget`() {
        val backend = Backend()
        val c = controller(backend)
        c.aim(off)
        for (time in listOf(0L, 250L, 1250L)) assertTrue(c.step(time) is PowerSaveReconciler.Result.Waiting)
        assertTrue(c.step(4250) is PowerSaveReconciler.Result.Failed)
        repeat(20) {
            c.aim(off)
            assertTrue(c.step(5000L + it) is PowerSaveReconciler.Result.Failed)
        }
        assertEquals(3, backend.requests.size)
        assertEquals(3, backend.clears)
    }

    @Test fun `native failure cannot skip independent framework cleanup`() {
        val backend = Backend().apply { nativeFailure = true }
        val c = controller(backend)
        c.aim(off)
        assertTrue(c.step(0) is PowerSaveReconciler.Result.Waiting)
        assertEquals(1, backend.clears)
        backend.nativeFailure = false
        c.step(250)
        assertEquals(listOf(off), backend.requests)
    }

    @Test fun `transient read failure retries without dropping the desired state`() {
        val backend = Backend().apply { readFailures = 1 }
        val c = controller(backend)
        c.aim(off)
        assertTrue(c.step(0) is PowerSaveReconciler.Result.Waiting)
        assertTrue(backend.requests.isEmpty())
        c.step(250)
        assertEquals(listOf(off), backend.requests)
    }

    @Test fun `quick unplug at low battery supersedes a queued exit despite stale enabled flag`() {
        val backend = Backend()
        val c = controller(backend)
        c.aim(off)
        c.step(0) // exit queued, native flag still says on
        assertEquals(true, BatteryAutoPowerSavePolicy.target(true, 20, false, true, 10))
        c.aim(on)
        assertTrue(c.step(10) is PowerSaveReconciler.Result.Waiting)
        assertEquals(listOf(off, on), backend.requests)
        assertEquals(1, backend.clears)
    }

    @Test fun `unplug above threshold clears the pending command and cancel preserves later manual choice`() {
        val backend = Backend().apply { state = PowerSaveReconciler.State(false, false) }
        val c = controller(backend)
        c.aim(off, force = true)
        c.step(0)
        assertEquals(listOf(off), backend.requests)
        assertTrue(c.step(250) is PowerSaveReconciler.Result.Confirmed)
        c.cancel()
        backend.state = PowerSaveReconciler.State(true, true)
        assertEquals(PowerSaveReconciler.Result.Idle, c.step(1000))
        assertEquals(1, backend.requests.size)
    }

    @Test fun `a new user or charging reenablement starts a separate reconciliation`() {
        val backend = Backend().apply { state = PowerSaveReconciler.State(false, false) }
        val c = controller(backend)
        c.aim(off)
        assertTrue(c.step(0) is PowerSaveReconciler.Result.Confirmed)
        backend.state = PowerSaveReconciler.State(true, true)
        c.aim(off)
        c.step(1)
        c.aim(PowerSaveReconciler.Target(10, false), force = true)
        c.step(2)
        assertEquals(listOf(off, PowerSaveReconciler.Target(10, false)), backend.requests)
    }
}
