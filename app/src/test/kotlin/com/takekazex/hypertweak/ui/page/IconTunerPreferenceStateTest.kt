package com.takekazex.hypertweak.ui.page

import org.junit.Assert.*
import org.junit.Test

class IconTunerPreferenceStateTest {
    private val key = "icon_stacked_signal_style"

    @Test fun rotationKeepsPendingEditsButResetAndRestoreInvalidateSavedValues() {
        val values = mutableMapOf<String, Any>()
        IconTunerPreferenceState.reconcileEpoch(values, 5)
        values[key] = 1
        val saved = IconTunerPreferenceState.save(values)
        val rotated = IconTunerPreferenceState.restore(saved).toMutableMap()
        IconTunerPreferenceState.reconcileEpoch(rotated, 5)
        assertEquals(1, rotated[key])
        IconTunerPreferenceState.reconcileEpoch(rotated, 6)
        assertFalse(rotated.containsKey(key))
        rotated[key] = 0
        val resetPayload = IconTunerPreferenceState.save(rotated)
        val restored = IconTunerPreferenceState.restore(resetPayload).toMutableMap()
        IconTunerPreferenceState.reconcileEpoch(restored, 7)
        assertFalse(restored.containsKey(key))
    }

    @Test fun oldPayloadAndTemporarilyUnavailableEpochAreHandledWithoutLosingPendingChanges() {
        val old = IconTunerPreferenceState.restore(listOf(key, 1)).toMutableMap()
        IconTunerPreferenceState.reconcileEpoch(old, null)
        assertEquals(1, old[key])
        IconTunerPreferenceState.reconcileEpoch(old, 0)
        assertFalse(old.containsKey(key))
        old[key] = 1
        IconTunerPreferenceState.reconcileEpoch(old, null)
        assertEquals(1, old[key])
        IconTunerPreferenceState.reconcileEpoch(old, 1)
        assertFalse(old.containsKey(key))
    }
}
