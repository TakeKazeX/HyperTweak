package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.settings.contract.BackGestureKeys

import org.junit.Assert.*
import org.junit.Test

class BackGestureSettingsSnapshotTest {
    private val indicator = BackGestureKeys.KEY_BACK_GESTURE_HYPEROS_INDICATOR
    private val slide = BackGestureKeys.KEY_BACK_GESTURE_SLIDE_ANIMATION
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
        val key = PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_HAPTICS
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
        assertEquals(false, restored.value(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_HAPTICS))
    }

    @Test fun `eight-flag reload preserves haptics and defaults fullscreen back off`() {
        val old = arrayOf<Any>(26L, 4L, true, false, false, false, true, false, true, true)
        val restored = requireNotNull(BackGestureSettingsSnapshot.restore(old))
        assertEquals(26L, restored.revision)
        assertEquals(4L, restored.epoch)
        assertEquals(true, restored.value(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_HAPTICS))
        assertEquals(false, restored.value(BackGestureKeys.KEY_FULLSCREEN_DIRECT_BACK))
    }

    @Test fun `fullscreen selection survives reload and rejects stale updates after reset`() {
        val key = BackGestureKeys.KEY_FULLSCREEN_DIRECT_BACK
        val enabled = baseline().mutate(key, true, 1)
        val disabled = enabled.mutate(key, false, 2)
        val ledger = BackGestureSettingsLedger()
        assertTrue(ledger.offer(requireNotNull(BackGestureSettingsSnapshot.restore(enabled.save()))))
        assertEquals(true, ledger.latest()?.value(key))
        assertTrue(ledger.offer(disabled))
        assertFalse(ledger.offer(enabled))
        assertEquals(false, ledger.latest()?.value(key))
        assertTrue(ledger.offer(baseline().copy(epoch = 1)))
        assertFalse(ledger.offer(enabled.copy(revision = 999)))
        assertEquals(false, ledger.latest()?.value(key))
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
    @Test fun `nine-flag upgrade preserves style and defaults new material and mute off`() {
        val old = arrayOf<Any>(31L, 5L, true, true, true, true, false, false, false, false, true)
        val restored = requireNotNull(BackGestureSettingsSnapshot.restore(old))
        assertEquals(31L, restored.revision)
        assertEquals(5L, restored.epoch)
        assertEquals(true, restored.value(BackGestureKeys.KEY_BACK_GESTURE_HYPEROS_HAPTICS))
        assertEquals(false, restored.value(BackGestureKeys.KEY_BACK_GESTURE_INDICATOR_BLUR))
        assertEquals(false, restored.value(BackGestureKeys.KEY_BACK_GESTURE_HAPTICS_DISABLED))
    }

    @Test fun `material and mute follow acknowledged reload disable and reset`() {
        val material = BackGestureKeys.KEY_BACK_GESTURE_INDICATOR_BLUR
        val mute = BackGestureKeys.KEY_BACK_GESTURE_HAPTICS_DISABLED
        val enabled = baseline().mutate(material, true, 1).mutate(mute, true, 2)
        val disabled = enabled.mutate(material, false, 3).mutate(mute, false, 4)
        val ledger = BackGestureSettingsLedger()
        assertTrue(ledger.offer(requireNotNull(BackGestureSettingsSnapshot.restore(disabled.save()))))
        assertFalse(ledger.offer(enabled))
        assertEquals(false, ledger.latest()?.value(material))
        assertEquals(false, ledger.latest()?.value(mute))
        assertTrue(ledger.offer(baseline().copy(epoch = 1)))
        assertFalse(ledger.offer(enabled.copy(revision = 999)))
    }

}
