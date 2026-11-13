package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class AppBarFeatureStateTest {
    @Test fun rejectedReloadCanRecoverWithoutAcceptingOldAttachment() {
        val state = AppBarFeatureState()
        state.setEnabled(true)
        val oldAttachment = state.generation()
        state.suspend()
        state.resumeAfterRejectedReload()
        assertTrue(state.enabled())
        assertFalse(state.accepts(oldAttachment))
        assertTrue(state.accepts(state.generation()))
    }

    @Test fun disabledAndReenabledPreferenceRejectsOldAttachment() {
        val state = AppBarFeatureState()
        assertFalse(state.enabled())
        state.setEnabled(true)
        val oldAttachment = state.generation()
        assertTrue(state.accepts(oldAttachment))
        state.setEnabled(false)
        assertFalse(state.accepts(oldAttachment))
        state.setEnabled(true)
        assertFalse(state.accepts(oldAttachment))
        assertTrue(state.accepts(state.generation()))
    }

    @Test fun repeatedPreferenceReceiptKeepsCurrentAttachment() {
        val state = AppBarFeatureState()
        state.setEnabled(true)
        val attachment = state.generation()
        state.setEnabled(true)
        assertTrue(state.accepts(attachment))
    }

    @Test fun reloadRejectsQueuedPreferenceAndActivationCallbacks() {
        val state = AppBarFeatureState()
        state.setEnabled(true)
        val attachment = state.generation()
        state.suspend()
        state.setEnabled(false)
        state.setEnabled(true)
        assertFalse(state.enabled())
        assertFalse(state.accepts(attachment))
        assertFalse(state.accepts(state.generation()))
        val replacement = AppBarFeatureState()
        replacement.setEnabled(true)
        assertTrue(replacement.accepts(replacement.generation()))
    }
}
