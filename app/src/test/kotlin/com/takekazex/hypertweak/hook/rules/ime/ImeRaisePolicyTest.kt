package com.takekazex.hypertweak.hook.rules.ime

import org.junit.Assert.*
import org.junit.Test

class ImeRaisePolicyTest {
    private fun desired(bottom: Int, padding: Int = 0) =
        ImeRaisePolicy.desiredPadding(600, bottom, padding, 144, 6)

    @Test fun insetHonoringKeyboardNeedsNoSecondRaise() {
        assertEquals(0, desired(456))
        assertEquals(0, desired(462))
        assertEquals(7, desired(463))
    }

    @Test fun repeatedLayoutsCountOurPaddingBackInWithoutAccumulation() {
        assertEquals(144, desired(600))
        repeat(10) { assertEquals(144, desired(456, 144)) }
        assertEquals(80, desired(536))
        repeat(10) { assertEquals(80, desired(456, 80)) }
    }

    @Test fun laterKeyboardClearanceWithdrawsOwnedRaise() {
        assertEquals(0, desired(312, 144))
        assertEquals(0, desired(456, 0))
        // The keyboard resets its clearance on the next show.
        assertEquals(144, desired(600))
    }

    @Test fun changedCaptionHeightRebasesExistingPadding() {
        assertEquals(72, ImeRaisePolicy.desiredPadding(600, 456, 144, 72, 6))
        assertEquals(0, ImeRaisePolicy.desiredPadding(600, 528, 72, 0, 6))
    }
}
