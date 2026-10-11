package com.takekazex.hypertweak.hook

import org.junit.Assert.*
import org.junit.Test

class BackGestureSettingsSnapshotTest {
    private val indicator = Preferences.KEY_BACK_GESTURE_HYPEROS_INDICATOR
    private val slide = Preferences.KEY_BACK_GESTURE_SLIDE_ANIMATION
    private fun baseline() = requireNotNull(BackGestureSettingsSnapshot.read(emptyMap<String, Any>()))

    @Test fun `switching back to AOSP rejects delayed HyperOS replay`() {
        val ledger = BackGestureSettingsLedger()
        val enabled = baseline().mutate(indicator, true, 1).mutate(slide, true, 2)
        val disabled = enabled.mutate(indicator, false, 3).mutate(slide, false, 4)
        assertTrue(ledger.offer(enabled))
        assertTrue(ledger.offer(disabled))
        assertFalse(ledger.offer(enabled))
        assertEquals(false, ledger.latest()?.value(indicator))
        assertEquals(false, ledger.latest()?.value(slide))
    }

    @Test fun `an unacknowledged failed edit cannot leak into the next successful edit`() {
        val ledger = BackGestureSettingsLedger()
        ledger.offer(baseline())
        val rejected = ledger.latest()!!.mutate(indicator, true, 10)
        val accepted = ledger.latest()!!.mutate(slide, true, 11)
        ledger.offer(accepted)
        assertEquals(false, accepted.value(indicator))
        assertEquals(true, accepted.value(slide))
        assertFalse(ledger.offer(rejected))
    }

    @Test fun `reset and imported configuration replace all switches in a new epoch`() {
        val ledger = BackGestureSettingsLedger()
        val old = baseline().copy(values = List(BackGestureSettingsSnapshot.KEYS.size) { true }, revision = 900, epoch = 1)
        ledger.offer(old)
        val reset = baseline().copy(epoch = 2)
        assertTrue(ledger.offer(reset))
        assertFalse(ledger.offer(old.copy(revision = 1000)))
        assertTrue(ledger.latest()!!.values.none { it })
        val restored = requireNotNull(BackGestureSettingsSnapshot.read(mapOf(
            slide to true, "prefs_epoch" to 3L)))
        assertTrue(ledger.offer(restored))
        assertEquals(listOf(slide), BackGestureSettingsSnapshot.KEYS.filter { ledger.latest()!!.value(it) == true })
    }

    @Test fun `same revision conflicting values cannot override a commit`() {
        val ledger = BackGestureSettingsLedger()
        val initial = baseline().mutate(indicator, true, 10)
        assertTrue(ledger.offer(initial))
        assertTrue(ledger.offer(initial))
        assertFalse(ledger.offer(initial.copy(values = baseline().values)))
        assertEquals(initial, ledger.latest())
    }

    @Test fun `hot reload primitive state preserves false and its revision against old true state`() {
        val committed = baseline().mutate(indicator, true, 1).mutate(indicator, false, 2)
        val replacement = BackGestureSettingsLedger()
        assertTrue(replacement.offer(requireNotNull(BackGestureSettingsSnapshot.restore(committed.save()))))
        assertFalse(replacement.offer(committed.copy(revision = 1, values = List(BackGestureSettingsSnapshot.KEYS.size) { true })))
        assertEquals(committed, replacement.latest())
        assertNull(BackGestureSettingsSnapshot.restore(arrayOf<Any>(2L, 0L, false)))
    }

    @Test fun `contextual search haptics remain live across disable reset and hot reload`() {
        val key = Preferences.KEY_CONTEXTUAL_SEARCH_HAPTICS
        val enabled = baseline().mutate(key, true, 1)
        val disabled = enabled.mutate(key, false, 2)
        val ledger = BackGestureSettingsLedger()
        assertTrue(ledger.offer(requireNotNull(BackGestureSettingsSnapshot.restore(disabled.save()))))
        assertFalse(ledger.offer(enabled))
        assertEquals(false, ledger.latest()!!.value(key))
        assertTrue(ledger.offer(baseline().copy(epoch = 1)))
        assertFalse(ledger.offer(enabled.copy(revision = 999)))
        assertEquals(false, ledger.latest()!!.value(key))
    }

    @Test fun `older seven-flag hot reload state preserves identity and opts out of haptics`() {
        val old = arrayOf<Any>(25L, 3L, true, false, false, false, true, false, true)
        val restored = requireNotNull(BackGestureSettingsSnapshot.restore(old))
        assertEquals(25L, restored.revision)
        assertEquals(3L, restored.epoch)
        assertEquals(true, restored.value(slide))
        assertEquals(false, restored.value(Preferences.KEY_CONTEXTUAL_SEARCH_HAPTICS))
    }

    @Test fun `malformed settings and negative identities cannot silently become defaults`() {
        for (bad in listOf<Any>("false", 0, 1L)) {
            assertNull(BackGestureSettingsSnapshot.read(mapOf(indicator to bad)))
        }
        for (bad in listOf<Any>(-1L, "1", 1)) {
            assertNull(BackGestureSettingsSnapshot.read(mapOf(BackGestureSettingsSnapshot.REVISION to bad)))
            assertNull(BackGestureSettingsSnapshot.read(mapOf("prefs_epoch" to bad)))
        }
    }
}
