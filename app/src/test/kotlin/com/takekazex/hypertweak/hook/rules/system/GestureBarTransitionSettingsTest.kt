package com.takekazex.hypertweak.hook.rules.system

import org.junit.Assert.*
import org.junit.Test

class GestureBarTransitionSettingsTest {
    @Test fun `defer overview detach but always preserve the owning transition final cleanup`() {
        assertEquals(GestureBarRestoreAction.DEFER, gestureBarRestoreAction(true, true, false, true))
        assertEquals(GestureBarRestoreAction.DEFER, gestureBarRestoreAction(true, true, true, true))
        assertEquals(GestureBarRestoreAction.NATIVE, gestureBarRestoreAction(false, true, true, true))
        assertEquals(GestureBarRestoreAction.NATIVE, gestureBarRestoreAction(false, true, false, true))
    }

    @Test fun `old recents callbacks cannot steal a token from the opening application`() {
        assertEquals(GestureBarRestoreAction.IGNORE_RETIRED, gestureBarRestoreAction(true, false, false, true))
        assertEquals(GestureBarRestoreAction.IGNORE_RETIRED, gestureBarRestoreAction(false, false, false, true))
        assertEquals(GestureBarRestoreAction.NATIVE, gestureBarRestoreAction(true, false, true, true))
        assertEquals(GestureBarRestoreAction.NATIVE, gestureBarRestoreAction(false, false, false, false))
    }

    @Test fun `enabling recents hiding does not revoke the requested follow-swipe attachment`() {
        val follow = GestureBarTransitionSettings(true, false)
        val combined = follow.copy(hideInRecents = true)
        assertTrue(follow.shouldAttach(false, true, false))
        assertTrue(combined.shouldAttach(false, true, false))
        assertTrue(combined.hideDisplayCopy)
    }

    @Test fun `disable and reset restore native defaults independently of recents hiding`() {
        for (hide in listOf(false, true)) {
            val disabled = GestureBarTransitionSettings(false, hide)
            assertFalse(disabled.shouldAttach(false, true, false))
            assertTrue(disabled.shouldAttach(true, true, false))
            assertFalse(disabled.hideDisplayCopy)
        }
        assertFalse(GestureBarTransitionSettings(false, false).shouldAttach(false, true, false))
    }

    @Test fun `native safety guards take precedence over both enabled switches`() {
        val combined = GestureBarTransitionSettings(true, true)
        assertFalse(combined.shouldAttach(true, false, false))
        assertFalse(combined.shouldAttach(true, true, true))
    }
}
