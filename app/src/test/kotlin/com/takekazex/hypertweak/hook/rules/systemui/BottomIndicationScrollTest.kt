package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomIndicationScrollTest {
    @Test fun fittingTextAndRoundingNoiseNeverScroll() {
        val scroll = BottomIndicationScroll()
        for (overflow in listOf(-10, 0, 1)) {
            assertEquals(0f, scroll.offset(0, overflow, 100f), 0f)
            assertEquals(0f, scroll.offset(5000, overflow, 100f), 0f)
        }
    }

    @Test fun pausesAtBothEndsAndReturnsWithoutWrapping() {
        val scroll = BottomIndicationScroll()
        assertEquals(0f, scroll.offset(0, 20, 100f), 0f)
        for (time in 16L..1488L step 16) assertEquals(0f, scroll.offset(time, 20, 100f), 0f)
        var offset = 0f
        for (time in 1504L..1792L step 16) offset = scroll.offset(time, 20, 100f)
        assertEquals(20f, offset, 0f)
        assertEquals(20f, scroll.offset(1808, 20, 100f), 0f)
        assertEquals(20f, scroll.offset(3000, 20, 100f), 0f)
        for (time in 3016L..3600L step 16) offset = scroll.offset(time, 20, 100f)
        assertEquals(0f, offset, 0f)
        assertEquals(0f, scroll.offset(3616, 20, 100f), 0f)
    }

    @Test fun liveWidthChangesPreserveProgressAndClampShrinkingContent() {
        val scroll = BottomIndicationScroll()
        scroll.offset(0, 300, 100f)
        for (time in 16L..2208L step 16) scroll.offset(time, 300, 100f)
        val before = scroll.offset(2224, 300, 100f)
        val updated = scroll.offset(2240, 310, 100f)
        assertTrue(updated > before)
        assertTrue(updated < before + 2f)
        assertEquals(10f, scroll.offset(2256, 10, 100f), 0f)
        assertEquals(0f, scroll.offset(2272, 0, 100f), 0f)
    }

    @Test fun hiddenTimeDoesNotCauseAJumpAndDetachRestartsAtLeft() {
        val scroll = BottomIndicationScroll()
        scroll.offset(0, 300, 100f)
        for (time in 16L..2208L step 16) scroll.offset(time, 300, 100f)
        val before = scroll.offset(2224, 300, 100f)
        scroll.pause()
        assertEquals(before, scroll.offset(20000, 300, 100f), 0f)
        scroll.reset()
        assertEquals(0f, scroll.offset(21000, 300, 100f), 0f)
    }
}
