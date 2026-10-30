package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeftHandoverTest {
    @Test fun overlayTravelsAcrossTheCompleteHostProgress() {
        assertEquals(0f, LeftHandover.travel(0f), 0f)
        assertEquals(0.5f, LeftHandover.travel(0.5f), 0f)
        assertEquals(1f, LeftHandover.travel(1f), 0f)
    }

    @Test fun overlayStaysOpaqueUntilFullArrival() {
        assertEquals(1f, LeftHandover.overlayFraction(0f), 0f)
        assertEquals(1f, LeftHandover.overlayFraction(0.75f), 0f)
        assertEquals(1f, LeftHandover.overlayFraction(0.99f), 0f)
        assertEquals(0f, LeftHandover.overlayFraction(1f), 0f)
    }

    @Test fun nativeEndpointTakesOverOnlyAtFullArrival() {
        assertEquals(0f, LeftHandover.destinationAlpha(0f), 0f)
        assertEquals(0f, LeftHandover.destinationAlpha(0.75f), 0f)
        assertEquals(0f, LeftHandover.destinationAlpha(0.99f), 0f)
        assertEquals(1f, LeftHandover.destinationAlpha(1f), 0f)
    }

    @Test fun sourceStaysVisibleUntilTheOverlayHasDrawn() {
        assertEquals(1f, LeftHandover.sourceAlpha(0.2f, false), 0f)
        assertEquals(0f, LeftHandover.sourceAlpha(0.2f, true), 0f)
        assertEquals(1f, LeftHandover.sourceAlpha(0f, true), 0f)
    }

    @Test fun reversingDragRetracesTheSamePath() {
        val points = listOf(0f, 0.3f, 0.75f, 0.9f, 1f)
        val forward = points.map(LeftHandover::travel)
        val reverse = points.reversed().map(LeftHandover::travel)
        assertEquals(forward, reverse.reversed())
        assertEquals(forward.sorted(), forward)
    }

    @Test fun progressOutsideTheDragIsClamped() {
        assertEquals(0f, LeftHandover.travel(-1f), 0f)
        assertEquals(1f, LeftHandover.travel(2f), 0f)
        assertEquals(1f, LeftHandover.overlayFraction(-1f), 0f)
        assertEquals(0f, LeftHandover.overlayFraction(2f), 0f)
        assertEquals(1f, LeftHandover.destinationAlpha(2f), 0f)
    }

    @Test fun repairTickCannotForceAPartialNativeDragToTheEndpoint() {
        assertEquals(.42f, LeftHandover.repairProgress(.42f,true,true), 0f)
        assertEquals(.42f, LeftHandover.repairProgress(.42f,true,false), 0f)
        assertEquals(1f, LeftHandover.repairProgress(.42f,false,true), 0f)
        assertEquals(.42f, LeftHandover.repairProgress(.42f,false,false), 0f)
    }
}
