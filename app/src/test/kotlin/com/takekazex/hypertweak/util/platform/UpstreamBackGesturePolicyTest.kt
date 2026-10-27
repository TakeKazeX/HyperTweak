package com.takekazex.hypertweak.util.platform

import org.junit.Assert.*
import org.junit.Test

class UpstreamBackGesturePolicyTest {
    @Test fun `installed upstream forces yielding unless debug detection is disabled`() {
        // Deliberately independent of upstream activation, feature switches and mapped payloads.
        assertTrue(UpstreamBackGesturePolicy.shouldYield(installed = true, detectionDisabled = false))
        assertFalse(UpstreamBackGesturePolicy.shouldYield(installed = true, detectionDisabled = true))
        assertFalse(UpstreamBackGesturePolicy.shouldYield(installed = false, detectionDisabled = false))
        assertFalse(UpstreamBackGesturePolicy.shouldYield(installed = false, detectionDisabled = true))
    }
}
