package com.takekazex.hypertweak.dock

import org.junit.Assert.*
import org.junit.Test

class DockCaptureGeometryTest {
    @Test fun lockedPortraitSourceDoesNotUseTheForegroundAppsLandscapeRotation() {
        assertEquals(0, DockCaptureGeometry.rotation(0, 0))
        assertEquals(2, DockCaptureGeometry.rotation(3, 3))
        assertNull(DockCaptureGeometry.rotation(-1, 0))
    }
    @Test fun nativeCaptureSwapsBeforeScalingAndRoundsUp() {
        assertEquals(600 to 1304, DockCaptureGeometry.buffer(1200, 2608, 0, .5f))
        assertEquals(1304 to 600, DockCaptureGeometry.buffer(1200, 2608, 1, .5f))
        assertEquals(6 to 10, DockCaptureGeometry.buffer(11, 19, 0, .5f))
        assertNull(DockCaptureGeometry.buffer(1200, 2608, 0, Float.NaN))
        assertNull(DockCaptureGeometry.buffer(1200, 2608, 0, 2f))
    }
}
