package com.takekazex.hypertweak.hook.rules.systemui.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockLongPressGestureTest {
    @Test fun `holding with small finger jitter toggles only once`() {
        val gesture = ClockLongPressGesture(100f, 20f, 8f)
        gesture.move(103f, 24f)
        gesture.move(98f, 22f)
        assertTrue(gesture.fire())
        assertFalse(gesture.fire())
    }

    @Test fun `pulling down from the clock never toggles seconds`() {
        val gesture = ClockLongPressGesture(100f, 20f, 8f)
        gesture.move(100f, 40f)
        assertFalse(gesture.fire())
    }

    @Test fun `moving away and back in a batched event cannot rearm the hold`() {
        val gesture = ClockLongPressGesture(100f, 20f, 8f)
        gesture.move(115f, 20f)
        gesture.move(100f, 20f)
        assertFalse(gesture.fire())
    }
}
