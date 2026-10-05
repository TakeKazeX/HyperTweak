package com.takekazex.hypertweak.hook

import org.junit.Assert.*
import org.junit.Test

class NativeRuleMutationTest {
    @Test fun `rejected editor shadow cannot contaminate a later successful native transaction`() {
        val ack = NativeSnapshotLedger()
        val stored = NativeRuleStateSnapshot(false, 4, true, 10, 3)
        ack.offer(stored)
        val failed = NativeRuleMutation.apply(stored, Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON, true, 11)
        // Editor.commit(false) may leave this different shadow; it is never acknowledged.
        assertTrue(failed.hidden)
        val next = NativeRuleMutation.apply(ack.latest()!!, Preferences.KEY_OPENED_FOLDER_COLUMNS, 5, 12)
        ack.offer(next)
        assertEquals(NativeRuleStateSnapshot(false, 5, true, 12, 3), ack.latest())
    }
    @Test fun `FIFO native commits preserve changes to independent switches`() {
        val ack = NativeSnapshotLedger()
        ack.offer(NativeRuleStateSnapshot(false, 4, false, 1, 1))
        ack.offer(NativeRuleMutation.apply(ack.latest()!!, Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON, true, 2))
        ack.offer(NativeRuleMutation.apply(ack.latest()!!, Preferences.KEY_OPENED_FOLDER_COLUMNS, 5, 3))
        assertEquals(NativeRuleStateSnapshot(true, 5, false, 3, 1), ack.latest())
    }
    @Test fun `canonical commit repairs failed shadow without overwriting newer queued intent`() {
        val stored = NativeRuleStateSnapshot(false, 4, true, 10, 3)
        val committed = NativeRuleMutation.apply(stored, Preferences.KEY_OPENED_FOLDER_COLUMNS, 5, 12)
        assertTrue(NativeRuleMutation.canMirror(committed, 3, 12))
        assertFalse(NativeRuleMutation.canMirror(committed, 3, 13))
        assertFalse(NativeRuleMutation.canMirror(committed, 4, 12))
    }
    @Test fun `widget toggle is retained across later independent commits but rejected shadow is discarded`() {
        val ack = NativeSnapshotLedger()
        val base = NativeRuleStateSnapshot(false, 4, false, 1, 1)
        ack.offer(base)
        val failed = NativeRuleMutation.apply(base, Preferences.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT, true, 2)
        assertTrue(failed.assistantWidgets)
        val next = NativeRuleMutation.apply(ack.latest()!!, Preferences.KEY_OPENED_FOLDER_COLUMNS, 5, 3)
        assertFalse(next.assistantWidgets)
        ack.offer(next)
        ack.offer(NativeRuleMutation.apply(ack.latest()!!, Preferences.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT, true, 4))
        ack.offer(NativeRuleMutation.apply(ack.latest()!!, Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON, true, 5))
        assertTrue(ack.latest()!!.assistantWidgets)
        assertTrue(ack.latest()!!.hidden)
    }
    @Test fun `unsupported native values cannot create a publication candidate`() {
        val base = NativeRuleStateSnapshot(false, 4, false)
        assertThrows(IllegalArgumentException::class.java) {
            NativeRuleMutation.apply(base, Preferences.KEY_OPENED_FOLDER_COLUMNS, 6, 1)
        }
        assertThrows(IllegalStateException::class.java) { NativeRuleMutation.apply(base, "unrelated", 5, 1) }
    }
}
