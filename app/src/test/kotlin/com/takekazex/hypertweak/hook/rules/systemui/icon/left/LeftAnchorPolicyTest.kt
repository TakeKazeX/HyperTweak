package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import com.takekazex.hypertweak.hook.rules.systemui.icon.left.LeftAnchorPolicy.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LeftAnchorPolicyTest {
    private val row = Level(horizontalRow = true, holdsSystemIcons = false)
    private val frame = Level(horizontalRow = false, holdsSystemIcons = false)
    private val barContents = Level(horizontalRow = true, holdsSystemIcons = true)
    private val barRoot = Level(horizontalRow = false, holdsSystemIcons = true)

    @Test
    fun stockClockJoinsItsOwnRow() {
        // clock -> phone_status_bar_left_container -> status_bar_contents -> root
        assertEquals(0, LeftAnchorPolicy.hostLevel(listOf(row, barContents, barRoot)))
    }

    @Test
    fun wrappedClockJoinsTheFirstRowAboveItsWrapper() {
        // A ROM wraps the clock in a FrameLayout or a vertical clock/date column.
        assertEquals(1, LeftAnchorPolicy.hostLevel(listOf(frame, row, barContents, barRoot)))
        assertEquals(2, LeftAnchorPolicy.hostLevel(listOf(frame, frame, row, barRoot)))
    }

    @Test
    fun aRowThatAlsoHoldsTheSystemIconsIsRejected() {
        // Joining it would place the icons beside the right cluster, not beside the clock.
        assertNull(LeftAnchorPolicy.hostLevel(listOf(frame, barContents, barRoot)))
        assertNull(LeftAnchorPolicy.hostLevel(listOf(barRoot)))
    }

    @Test
    fun noHorizontalAncestorIsRejected() {
        assertNull(LeftAnchorPolicy.hostLevel(listOf(frame, frame)))
        assertNull(LeftAnchorPolicy.hostLevel(emptyList()))
    }
}
