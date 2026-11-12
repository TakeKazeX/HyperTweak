package com.takekazex.hypertweak.ui.page.systemui.icon

import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSettingsPreferencePolicy
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

    @Test fun stackedSignalAlwaysUsesTheSeparateNativeType() {
        val values = mutableMapOf<String, Any>(IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_SINGLE to false)
        assertFalse(IconSettingsPreferencePolicy.separateCellularType { values[it] as? Boolean })
        values[IconSettingsKeys.KEY_ICON_STACKED_ENABLED] = true
        assertTrue(IconSettingsPreferencePolicy.separateCellularType { values[it] as? Boolean })
    }

    @Test fun currentWifiAndSimSwitchesOverrideLegacyValuesEvenWhenTurnedOff() {
        val values = mutableMapOf(
            IconSettingsKeys.KEY_ICON_STACKED_TYPE_HIDE_WIFI to false,
            IconSettingsKeys.KEY_ICON_HIDE_SIM_AUTO to true
        )

        assertTrue(IconSettingsPreferencePolicy.keepCellularTypeOnWifi(values::get))
        assertTrue(IconSettingsPreferencePolicy.hideNonDefaultSim(values::get))

        values[IconSettingsKeys.KEY_ICON_CELLULAR_TYPE_KEEP_ON_WIFI] = false
        values[IconSettingsKeys.KEY_ICON_HIDE_NON_DEFAULT_SIM] = false
        assertFalse(IconSettingsPreferencePolicy.keepCellularTypeOnWifi(values::get))
        assertFalse(IconSettingsPreferencePolicy.hideNonDefaultSim(values::get))

        values.clear()
        assertFalse(IconSettingsPreferencePolicy.keepCellularTypeOnWifi(values::get))
        assertFalse(IconSettingsPreferencePolicy.hideNonDefaultSim(values::get))
    }

    @Test fun explicitWifiStandardModeWinsOverLegacyHide() {
        assertEquals(1, IconSettingsPreferencePolicy.wifiStandardMode(null, true))
        assertEquals(0, IconSettingsPreferencePolicy.wifiStandardMode(0, true))
        assertEquals(3, IconSettingsPreferencePolicy.wifiStandardMode(3, true))
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
