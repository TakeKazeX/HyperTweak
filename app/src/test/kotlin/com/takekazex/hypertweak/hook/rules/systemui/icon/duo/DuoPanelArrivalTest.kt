package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoPanelArrivalTest {
    @Test fun temporaryEndpointAndTargetLossDoNotReplayAnExpandedPanelArrival() {
        val arrival = DuoPanelArrival()
        arrival.observe(1f, visible = true)
        assertFalse(arrival.settled)
        arrival.arrive()
        arrival.observe(1f, visible = true) // Wi-Fi target changes while expanded.
        arrival.observe(0f, visible = true) // Background mode reports a temporary endpoint.
        arrival.observe(1f, visible = true)
        assertTrue(arrival.settled)
    }

    @Test fun aRealDragOrDismissalArmsTheNextPanelArrival() {
        val arrival = DuoPanelArrival(initiallySettled = true)
        arrival.observe(.75f, visible = true)
        assertFalse(arrival.settled)
        arrival.arrive()
        arrival.observe(0f, visible = false)
        assertFalse(arrival.settled)
        arrival.shadeFinished(controlCenterVisible = true)
        assertTrue(arrival.settled)
        arrival.shadeFinished(controlCenterVisible = false)
        assertFalse(arrival.settled)
    }
}
