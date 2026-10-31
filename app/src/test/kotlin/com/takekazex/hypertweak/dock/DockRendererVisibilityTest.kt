package com.takekazex.hypertweak.dock

import org.junit.Assert.*
import org.junit.Test

class DockRendererVisibilityTest {
    @Test fun visibleLeaseProtectsOnlyItsRendererUid() {
        val state = DockRendererVisibility()
        assertFalse(state.protects(10282))
        state.update("home", 10282, true)
        assertTrue(state.protects(10282))
        assertFalse(state.protects(10151))
        assertFalse(state.protects(1000))
    }

    @Test fun inactiveScreenSessionAndDisableReleaseProtection() {
        val state = DockRendererVisibility()
        state.update("home", 10282, true)
        state.update("home", 10282, false)
        assertFalse(state.protects(10282))
        state.update("home", 10282, true)
        assertTrue(state.protects(10282))
        state.remove("home")
        assertFalse(state.protects(10282))
    }

    @Test fun replacementAndMultipleWindowsRetainOnlyLiveVisibleOwners() {
        val state = DockRendererVisibility()
        state.update("first", 10282, true)
        state.update("second", 10282, true)
        state.remove("first")
        assertTrue(state.protects(10282))
        state.update("second", 110282, true)
        assertFalse(state.protects(10282))
        assertTrue(state.protects(110282))
        state.update("second", -1, true)
        assertFalse(state.protects(110282))
    }

    @Test fun retiredGenerationCannotPublishProtectionAgain() {
        val old = DockRendererVisibility()
        old.update("home", 10282, true)
        old.close()
        old.update("late-create", 10282, true)
        assertFalse(old.protects(10282))
        val replacement = DockRendererVisibility()
        replacement.update("home", 10282, true)
        old.remove("home")
        assertTrue(replacement.protects(10282))
    }
}
