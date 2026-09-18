package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import org.junit.Assert.assertEquals
import org.junit.Test

class DuoPanelGeometryTest {
    @Test fun bothAxesReachHomeBeforeTheFingerIsReleased() {
        val home = DuoPanelPoint(980f, 40f)
        val expanded = DuoPanelPoint(930f, 160f)
        assertEquals(home, DuoPanelGeometry.position(home, expanded, 0f))
        assertEquals(DuoPanelPoint(955f, 100f), DuoPanelGeometry.position(home, expanded, .5f))
        assertEquals(expanded, DuoPanelGeometry.position(home, expanded, 1f))
        val progress = listOf(0f, .25f, .75f, 1f)
        assertEquals(progress.map { DuoPanelGeometry.position(home, expanded, it) },
            progress.reversed().map { DuoPanelGeometry.position(home, expanded, it) }.reversed())
    }

    @Test fun compensationIsIdempotentAndRestoresOnlyItsOwnOffset() {
        val offset = DuoOwnedTranslation()
        assertEquals(30f, offset.apply(100f, -70f), 0f)
        assertEquals(30f, offset.apply(30f, 0f), 0f)
        assertEquals(100f, offset.restore(30f), 0f)
        offset.apply(100f, -70f)
        assertEquals(45f, offset.restore(45f), 0f) // a host update must survive cleanup
        offset.apply(100f, -70f)
        assertEquals(40f, offset.apply(45f, -5f), 0f)
        assertEquals(45f, offset.restore(40f), 0f)
    }

    @Test fun changedFakeRowBaselineStillConvergesToTheActualHomeCenter() {
        val offset = DuoOwnedTranslation()
        val home = DuoPanelPoint(980f, 40f)
        val expanded = DuoPanelPoint(930f, 160f)
        val fakeCenter = 190f // fake row has a different height/baseline from the new battery row
        var translation = 0f
        for (progress in listOf(1f, .5f, .1f, 0f, .3f, 0f)) {
            val desired = DuoPanelGeometry.position(home, expanded, progress)
            translation = offset.apply(translation, desired.y - (fakeCenter + translation))
            assertEquals(desired.y, fakeCenter + translation, .001f)
        }
        assertEquals(home.y, fakeCenter + translation, .001f)
        assertEquals(0f, offset.restore(translation), .001f)
    }

    @Test fun legitimateHostStretchIsNotLostByEndpointCorrection() {
        assertEquals(DuoPanelPoint(960f, 88f), DuoPanelGeometry.position(
            DuoPanelPoint(980f, 40f), DuoPanelPoint(930f, 160f), .5f, DuoPanelPoint(5f, -12f)))
    }

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
