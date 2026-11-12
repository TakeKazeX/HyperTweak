package com.takekazex.hypertweak.ui.page.systemui.icon

import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconConfigDecoder
import com.takekazex.hypertweak.settings.contract.SignalStyle
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

    @Test fun retiredKeysMigrateUntilTheCurrentStyleIsStored() {
        val legacy = mapOf<String, Any>(IconSettingsKeys.KEY_ICON_STACKED_ENABLED to true,
            IconSettingsKeys.KEY_ICON_DUO_ENABLED to true,
            IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_WIFI to false,
            IconSettingsKeys.KEY_ICON_HIDE_SIM_AUTO to true)
        val migrated = IconConfigDecoder.decode { legacy[it] }
        assertEquals(SignalStyle.DUO, migrated.signalStyle)
        assertEquals(true, migrated.value(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI))
        assertEquals(true, migrated.value(IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM))
        for (style in SignalStyle.entries) {
            val values = legacy + (IconSettingsKeys.KEY_ICON_SIGNAL_STYLE to style.ordinal)
            val config = IconConfigDecoder.decode { values[it] }
            assertEquals(style, config.signalStyle)
            assertEquals(style == SignalStyle.STACKED, config.value(IconSettingsKeys.KEY_ICON_STACKED_ENABLED))
            assertEquals(style == SignalStyle.DUO, config.value(IconSettingsKeys.KEY_ICON_DUO_ENABLED))
        }
        assertEquals(SignalStyle.NORMAL, IconConfigDecoder.decode { 99 }.signalStyle)
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
