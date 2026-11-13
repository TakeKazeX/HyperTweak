package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class LauncherConnectionSessionTest {
    @Test fun connectedBaselineCanAdvertiseWithoutRestartingMonitor() {
        val state = LauncherConnectionSession()
        val binder = Any()
        val receipt = requireNotNull(state.observe(binder, 7))
        assertTrue(state.accepts(receipt, binder, 7))
        assertNull(state.observe(binder, 7))
        assertTrue(state.accepts(receipt, binder, 7))
    }

    @Test fun disconnectInvalidatesQueuedAdvertisementEvenIfSameBinderReconnects() {
        val state = LauncherConnectionSession()
        val binder = Any()
        val old = requireNotNull(state.observe(binder, 7))
        assertNull(state.observe(null, 7))
        assertFalse(state.accepts(old, binder, 7))
        val replacement = requireNotNull(state.observe(binder, 7))
        assertFalse(state.accepts(old, binder, 7))
        assertTrue(state.accepts(replacement, binder, 7))
    }

    @Test fun silentlyReplacedBinderCannotReceiveOldQueuedAdvertisement() {
        val state = LauncherConnectionSession()
        val first = Any()
        val second = Any()
        val old = requireNotNull(state.observe(first, 7))
        assertFalse(state.accepts(old, second, 7))
        val current = requireNotNull(state.observe(second, 7))
        assertFalse(state.accepts(old, first, 7))
        assertTrue(state.accepts(current, second, 7))
    }

    @Test fun generationChangeRejectsAnOldConnectionReceipt() {
        val state = LauncherConnectionSession()
        val binder = Any()
        val old = requireNotNull(state.observe(binder, 7))
        assertFalse(state.accepts(old, binder, 8))
        val current = requireNotNull(state.observe(binder, 8))
        assertFalse(state.accepts(old, binder, 7))
        assertTrue(state.accepts(current, binder, 8))
        assertNull(state.observe(binder, 0))
        assertFalse(state.accepts(current, binder, 8))
    }

    @Test fun hotReloadClosesEveryQueuedReceipt() {
        val state = LauncherConnectionSession()
        val binder = Any()
        val old = requireNotNull(state.observe(binder, 7))
        state.close()
        assertFalse(state.accepts(old, binder, 7))
        assertNull(state.observe(binder, 7))
    }
}
