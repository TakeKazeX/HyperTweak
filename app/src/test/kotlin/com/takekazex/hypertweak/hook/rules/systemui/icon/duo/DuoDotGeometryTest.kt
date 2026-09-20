package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class DuoDotGeometryTest {
    @Test fun allDotsCompleteTheTransformedRingWithoutClipping() {
        for (index in 0..3) {
            val x = DuoDotGeometry.x[index]
            val y = DuoDotGeometry.y[index]
            assertEquals(DuoDotGeometry.radius,
                hypot(x - DuoDrawable.CENTER, y - DuoDrawable.COMPACT_RING_CENTER_Y), .001f)
            assertTrue(x - DuoDrawable.SIGNAL_DOT_RADIUS > 0)
            assertTrue(x + DuoDrawable.SIGNAL_DOT_RADIUS < DuoDrawable.VIEWPORT)
            assertTrue(y + DuoDrawable.SIGNAL_DOT_RADIUS < DuoDrawable.VIEWPORT)
            if (index > 0) assertTrue(x - DuoDotGeometry.x[index - 1] > 2 * DuoDrawable.SIGNAL_DOT_RADIUS)
        }
        assertEquals(DuoDotGeometry.y[0], DuoDotGeometry.y[3], .001f)
        assertEquals(DuoDotGeometry.y[1], DuoDotGeometry.y[2], .001f)
    }
}
