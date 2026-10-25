package com.takekazex.hypertweak.hook.rules.screenshot

import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsKeys


import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsLedger

import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsSnapshot

import org.junit.Assert.*
import org.junit.Test

class ScreenshotSettingsSnapshotTest {
    private val enabledKey = ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME
    private val modeKey = ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE

    @Test fun `next capture uses the pushed value even when remote preferences stay stale`() {
        val mainProcess = ScreenshotSettingsLedger()
        val editorProcess = ScreenshotSettingsLedger()
        val old = ScreenshotSettingsSnapshot.read(1, mapOf(enabledKey to false, modeKey to 0))!!
        mainProcess.accept(old)
        editorProcess.accept(old)
        val committed = old.mutate(enabledKey, true, 2)!!.mutate(modeKey, 1, 3)!!
        assertTrue(mainProcess.accept(committed))
        assertTrue(editorProcess.accept(committed))
        assertTrue(mainProcess.latest()!!.enabled)
        assertEquals(1, editorProcess.latest()!!.editorMode)
        assertFalse(mainProcess.accept(old))
        assertFalse(editorProcess.accept(old))
    }

    @Test fun `rejected mutation cannot contaminate a later complete committed snapshot`() {
        val acknowledged = ScreenshotSettingsLedger()
        val baseline = ScreenshotSettingsSnapshot(1, false, 0)
        acknowledged.accept(baseline)
        val rejected = baseline.mutate(modeKey, 1, 2)!!
        // No acknowledgement for a rejected commit; the next write starts from the old ledger.
        val next = acknowledged.latest()!!.mutate(enabledKey, true, 3)!!
        assertEquals(1, rejected.editorMode)
        assertEquals(0, next.editorMode)
        assertTrue(next.enabled)
        assertTrue(acknowledged.accept(next))
    }

    @Test fun `hot reload preserves pushed values and rejects delayed or conflicting snapshots`() {
        val old = ScreenshotSettingsLedger()
        old.accept(ScreenshotSettingsSnapshot(8, true, 1))
        val restored = ScreenshotSettingsLedger().also { it.restore(old.save()) }
        assertFalse(restored.accept(ScreenshotSettingsSnapshot(7, false, 0)))
        assertFalse(restored.accept(ScreenshotSettingsSnapshot(8, false, 0)))
        assertEquals(ScreenshotSettingsSnapshot(8, true, 1), restored.latest())
        assertTrue(restored.accept(ScreenshotSettingsSnapshot.read(9, emptyMap<String, Any>())!!))
        assertEquals(ScreenshotSettingsSnapshot(9, false, 0), restored.latest())
    }

    @Test fun `mode updates apply to the next editor without replacing the current source`() {
        val ledger = ScreenshotSettingsLedger()
        ledger.accept(ScreenshotSettingsSnapshot(1, true, 0))
        val sessions = ScreenshotEditorSessions { ledger.latest()!!.editorMode }
        val current = Any()
        assertEquals(0, sessions.mode(current))
        ledger.accept(ScreenshotSettingsSnapshot(2, true, 1))
        assertEquals(0, sessions.mode(current))
        assertEquals(1, sessions.mode(Any()))
        sessions.release(current)
        assertEquals(1, sessions.mode(current))
    }

    @Test fun `clipboard copy survives hot reload and a pre-copy payload restores it off`() {
        val ledger = ScreenshotSettingsLedger()
        val old = ScreenshotSettingsSnapshot.read(1, mapOf(enabledKey to false, modeKey to 0))!!
        assertFalse(old.copyToClipboard)
        ledger.accept(old)
        assertTrue(ledger.latest()!!.mutate(ScreenshotSettingsKeys.KEY_SCREENSHOT_COPY_TO_CLIPBOARD, true, 2)!!.let(ledger::accept))
        assertTrue(ScreenshotSettingsLedger().also { it.restore(ledger.save()) }.latest()!!.copyToClipboard)
        // An older payload that predates the switch cannot resurrect a copy request.
        val legacy = ScreenshotSettingsLedger().also { it.restore(arrayOf<Any>(3L, false, 0)) }
        assertFalse(legacy.latest()!!.copyToClipboard)
        assertEquals(3L, legacy.latest()!!.revision)
    }

    @Test fun `directory enable disable reset and legacy hot reload use acknowledged values`() {
        val key = ScreenshotSettingsKeys.KEY_SCREENSHOT_PICTURES_DIRECTORY
        val baseline = ScreenshotSettingsSnapshot(1, true, 1, true)
        val enabled = baseline.mutate(key, true, 2)!!
        assertTrue(enabled.picturesDirectory)
        assertTrue(enabled.copyToClipboard)
        val ledger = ScreenshotSettingsLedger().also { it.accept(enabled) }
        assertEquals(enabled, ScreenshotSettingsLedger().also { it.restore(ledger.save()) }.latest())
        assertFalse(enabled.mutate(key, false, 3)!!.picturesDirectory)
        assertFalse(ScreenshotSettingsSnapshot.read(4, emptyMap<String, Any>())!!.picturesDirectory)
        val legacy = ScreenshotSettingsLedger().also { it.restore(arrayOf<Any>(3L, true, 1, true)) }
        assertTrue(legacy.latest()!!.copyToClipboard)
        assertFalse(legacy.latest()!!.picturesDirectory)
        assertNull(ScreenshotSettingsSnapshot.read(5, mapOf(key to "true")))
    }

    @Test fun `malformed types revisions and modes cannot become runtime settings`() {
        assertNull(ScreenshotSettingsSnapshot.read(0, emptyMap<String, Any>()))
        assertNull(ScreenshotSettingsSnapshot.read(1, mapOf(enabledKey to "true")))
        assertNull(ScreenshotSettingsSnapshot.read(1, mapOf(modeKey to 2)))
        assertNull(ScreenshotSettingsSnapshot.read(1, mapOf(modeKey to 1L)))
        val ledger = ScreenshotSettingsLedger()
        ledger.restore(arrayOf<Any>(1L, true))
        ledger.restore(arrayOf<Any>(1L, true, "1"))
        assertNull(ledger.latest())
        assertNull(ScreenshotSettingsSnapshot(1, false, 0).mutate("unrelated", true, 2))
    }
}
