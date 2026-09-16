package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the "limit notification icons" numeric contract.
 *
 * The settings row derives its slider step count from [NotificationIconLimit.sliderSteps] and the
 * hook clamps through [NotificationIconLimit.clamp]. If the step derivation is wrong the slider
 * silently cannot reach some integers, and if the bounds drift apart the UI offers values the hook
 * rewrites without telling anyone.
 */
class NotificationIconLimitTest {

    /** The spec as requested: minimum 0, default 3, maximum 15. */
    @Test
    fun contractIsMinimumZeroDefaultThreeMaximumFifteen() {
        assertEquals(0, NotificationIconLimit.MIN)
        assertEquals(3, NotificationIconLimit.DEFAULT)
        assertEquals(15, NotificationIconLimit.MAX)

        // RANGE is derived from MIN/MAX, so it cannot disagree with them.
        assertEquals(0, NotificationIconLimit.RANGE.first)
        assertEquals(15, NotificationIconLimit.RANGE.last)
    }

    @Test
    fun clampAcceptsTheRangeAndRejectsOutsideIt() {
        assertEquals(0, NotificationIconLimit.clamp(0))
        assertEquals(3, NotificationIconLimit.clamp(3))
        assertEquals(15, NotificationIconLimit.clamp(15))
        assertEquals(0, NotificationIconLimit.clamp(-1))
        assertEquals(15, NotificationIconLimit.clamp(20))
    }

    /**
     * Miuix snaps through `round(fraction * (steps + 1))`, so `steps + 2` positions must equal the
     * number of integers in the range for every value to be reachable.
     */
    @Test
    fun sliderStepsMakeEveryIntegerReachable() {
        val positions = NotificationIconLimit.sliderSteps() + 2
        assertEquals(NotificationIconLimit.RANGE.last - NotificationIconLimit.RANGE.first + 1, positions)

        // The same derivation for a degenerate and a one-step range must not go negative.
        assertEquals(0, NotificationIconLimit.sliderSteps(0..0))
        assertEquals(0, NotificationIconLimit.sliderSteps(0..1))
        assertEquals(1, NotificationIconLimit.sliderSteps(0..2))
    }
}
