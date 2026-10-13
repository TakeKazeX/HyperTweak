package com.takekazex.hypertweak.hook.rules.screenshot

import org.junit.Assert.*
import org.junit.Test

class ScreenshotFramePolicyTest {
    @Test fun `editor user prefix and media volume aliases identify the same own-user image`() {
        val key = ScreenshotFramePolicy.mediaKey("content://media/external/images/media/25443", 0)
        assertNotNull(key)
        assertEquals(key, ScreenshotFramePolicy.mediaKey("content://0@media/external/images/media/25443", 0))
        assertEquals(key, ScreenshotFramePolicy.mediaKey("content://media/external_primary/images/media/25443", 0))
        assertNull(ScreenshotFramePolicy.mediaKey("content://10@media/external/images/media/25443", 0))
        assertNull(ScreenshotFramePolicy.mediaKey("content://another.provider/external/images/media/25443", 0))
        assertNotEquals(key, ScreenshotFramePolicy.mediaKey("content://media/external/images/media/25444", 0))
    }
    @Test fun `only complete primary display portrait capture is eligible`() {
        assertTrue(ScreenshotFramePolicy.eligible(1, 0, 1200, 2608, 1200, 2608))
        assertFalse(ScreenshotFramePolicy.eligible(3, 0, 1200, 2608, 1200, 2608))
        assertFalse(ScreenshotFramePolicy.eligible(1, 1, 1200, 2608, 1200, 2608))
        assertFalse(ScreenshotFramePolicy.eligible(1, 0, 600, 1304, 1200, 2608))
        assertFalse(ScreenshotFramePolicy.eligible(1, 0, 1200, 9000, 1200, 2608))
        assertFalse(ScreenshotFramePolicy.eligible(1, 0, 2608, 1200, 2608, 1200))
        assertFalse(ScreenshotFramePolicy.eligible(1, 0, 1200, 2608, 2608, 1200))
    }

    @Test fun `material can scale a screenshot but cannot distort another aspect ratio`() {
        assertTrue(ScreenshotFramePolicy.matchesMaterial(1200, 2608, 1200f, 2608f))
        assertTrue(ScreenshotFramePolicy.matchesMaterial(900, 1956, 1200f, 2608f))
        assertFalse(ScreenshotFramePolicy.matchesMaterial(1286, 2689, 1200f, 2608f))
        assertFalse(ScreenshotFramePolicy.matchesMaterial(1200, 2608, 1200f, Float.NaN))
        assertFalse(ScreenshotFramePolicy.matchesMaterial(1200, 2608, 0f, 0f))
    }

    @Test fun `invalid or excessive bitmap allocations are rejected without integer overflow`() {
        assertTrue(ScreenshotFramePolicy.safeOutput(1286f, 2689f))
        assertFalse(ScreenshotFramePolicy.safeOutput(0f, 2689f))
        assertFalse(ScreenshotFramePolicy.safeOutput(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))
        assertFalse(ScreenshotFramePolicy.safeOutput(100_000f, 200_000f))
        assertFalse(ScreenshotFramePolicy.eligible(1, 0, Int.MAX_VALUE - 1, Int.MAX_VALUE,
            Int.MAX_VALUE - 1, Int.MAX_VALUE))
    }
}
