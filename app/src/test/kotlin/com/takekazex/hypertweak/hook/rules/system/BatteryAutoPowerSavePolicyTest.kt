package com.takekazex.hypertweak.hook.rules.system

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryAutoPowerSavePolicyTest {
    private fun target(
        level: Int = 100, charging: Boolean = false, enabled: Boolean = true,
        exitWhenCharging: Boolean = true, threshold: Int = 20, justPlugged: Boolean = false, justUnplugged: Boolean = false,
    ) = BatteryAutoPowerSavePolicy.target(enabled, threshold, charging, exitWhenCharging, level, justPlugged, justUnplugged)

    @Test fun `plug edge closes saver once at every battery level`() {
        for (level in listOf(0, 5, 20, 100)) {
            assertEquals(false, target(level, charging = true, justPlugged = true))
            assertEquals(null, target(level, charging = true))
        }
    }
    @Test fun `manual enable while charging survives later samples and notifications`() {
        assertEquals(false, target(charging = true, justPlugged = true))
        repeat(20) { assertEquals(null, target(charging = true)) }
        assertEquals(null, target(5, charging = true))
    }
    @Test fun `disabled charging switch never closes saver on connection`() {
        assertEquals(null, target(charging = true, justPlugged = true, exitWhenCharging = false))
    }
    @Test fun `startup already connected is a baseline and preserves manual saver`() {
        assertEquals(null, target(charging = true, justPlugged = false))
    }
    @Test fun `unplug only follows independent low battery automation`() {
        assertEquals(null, target(100))
        assertEquals(true, target(20))
        assertEquals(true, target(5))
        assertEquals(null, target(5, enabled = false))
    }
    @Test fun `threshold clamps and invalid samples leave state alone`() {
        assertEquals(true, target(10, threshold = 5))
        assertEquals(null, target(11, threshold = 5))
        assertEquals(true, target(50, threshold = 90))
        assertEquals(null, target(51, threshold = 90))
        assertEquals(null, target(-1, charging = true, justPlugged = true))
        assertEquals(null, target(101, charging = true, justPlugged = true))
    }
    @Test fun `connection broadcasts and battery samples close only once per plug`() {
        val tracker = ChargerConnectionTracker()
        assertEquals(false, tracker.update(false))
        assertEquals(true, tracker.update(true))
        repeat(20) { assertEquals(false, tracker.update(true)) }
        assertEquals(false, tracker.update(false))
        assertEquals(true, tracker.update(true))
    }
    @Test fun `tracker startup while plugged never closes manually enabled saver`() {
        val tracker = ChargerConnectionTracker()
        assertEquals(false, tracker.update(true))
        assertEquals(false, tracker.update(true))
    }
    @Test fun `queued disconnect followed by replug is detected even when sticky stayed connected`() {
        val tracker = ChargerConnectionTracker()
        assertEquals(false, tracker.update(true))
        assertEquals(false, tracker.update(true, disconnectedEvent = true))
        assertEquals(true, tracker.update(true))
        assertEquals(false, tracker.update(true))
    }

    @Test fun `unplug within threshold restores saver even with automatic enable off`() {
        for (level in listOf(0, 10, 19, 20)) {
            assertEquals(true, target(level, enabled = false, justUnplugged = true))
            assertEquals(null, target(level, enabled = false))
        }
    }
    @Test fun `unplug above threshold leaves saver explicitly off`() {
        assertEquals(false, target(21, enabled = false, justUnplugged = true))
        assertEquals(false, target(100, justUnplugged = true))
        assertEquals(null, target(100))
    }
    @Test fun `unplug threshold action belongs to the charging switch`() {
        assertEquals(null, target(10, enabled = false, exitWhenCharging = false, justUnplugged = true))
        assertEquals(null, target(100, exitWhenCharging = false, justUnplugged = true))
    }
    @Test fun `disconnect edge is emitted once and never at cold startup`() {
        val tracker = ChargerConnectionTracker()
        tracker.update(false)
        assertEquals(false, tracker.justDisconnected)
        tracker.update(true)
        tracker.update(false)
        assertEquals(true, tracker.justDisconnected)
        tracker.update(false, disconnectedEvent = true)
        assertEquals(false, tracker.justDisconnected)
        tracker.update(false)
        assertEquals(false, tracker.justDisconnected)
    }

}
