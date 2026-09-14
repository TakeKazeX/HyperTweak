package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import org.junit.Assert.assertEquals
import org.junit.Test

class DuoPanelGeometryTest {
    @Test fun movingLayerStaysOpaqueUntilTheNativeHandoff() {
        assertEquals(255, DuoPanelGeometry.overlayAlpha(0f))
        assertEquals(255, DuoPanelGeometry.overlayAlpha(.5f))
        assertEquals(255, DuoPanelGeometry.overlayAlpha(.75f))
        assertEquals(127, DuoPanelGeometry.overlayAlpha(.875f))
        assertEquals(0, DuoPanelGeometry.overlayAlpha(1f))
    }

    @Test fun actualEndpointsHaveNoNativeWidthOffset() {
        assertEquals(980f, DuoPanelGeometry.mix(980f, 930f, 0f), 0f)
        assertEquals(955f, DuoPanelGeometry.mix(980f, 930f, .5f), 0f)
        assertEquals(930f, DuoPanelGeometry.mix(980f, 930f, 1f), 0f)
    }
    @Test fun reversingDragRetracesTheSamePath() {
        val forward = listOf(0f, .2f, .7f, 1f).map { DuoPanelGeometry.mix(980f, 930f, it) }
        val reverse = listOf(1f, .7f, .2f, 0f).map { DuoPanelGeometry.mix(980f, 930f, it) }
        assertEquals(forward, reverse.reversed())
    }
}
