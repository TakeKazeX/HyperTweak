package com.takekazex.hypertweak.hook.preferences

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsSignal
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.PowerSaveOverrideSelection

/** Power-save overrides and the automatic power-save threshold. */
internal object PowerSavePrefs {
    // The removed master is retained only to interpret legacy snapshots. Each new individual
    // edit atomically flattens the effective selection and permanently opens that legacy gate.
    private fun featureEnabled(key: String): Boolean =
        Preferences.getBoolean(PlatformSettingsKeys.KEY_POWER_SAVE_OVERRIDE_MASTER, false) && Preferences.getBoolean(key, false)

    fun keepRefreshRateInPowerSave(): Boolean = featureEnabled(PlatformSettingsKeys.KEY_KEEP_REFRESH_RATE_IN_POWER_SAVE)
    fun keepHapticFeedbackInPowerSave(): Boolean = featureEnabled(PlatformSettingsKeys.KEY_KEEP_HAPTIC_FEEDBACK_IN_POWER_SAVE)
    fun keepSystemSoundsInPowerSave(): Boolean = featureEnabled(PlatformSettingsKeys.KEY_KEEP_SYSTEM_SOUNDS_IN_POWER_SAVE)
    fun keepWakeupGesturesInPowerSave(): Boolean = featureEnabled(PlatformSettingsKeys.KEY_KEEP_WAKEUP_GESTURES_IN_POWER_SAVE)

    /** The low-battery threshold in percent, bounded to the slider's band. */
    fun batteryAutoPowerSaveThreshold(): Int = Preferences.getInt(
        PlatformSettingsKeys.KEY_BATTERY_AUTO_POWER_SAVE_THRESHOLD,
        PlatformSettingsKeys.DEFAULT_BATTERY_AUTO_POWER_SAVE_THRESHOLD,
    ).coerceIn(PlatformSettingsKeys.MIN_BATTERY_AUTO_POWER_SAVE_THRESHOLD, PlatformSettingsKeys.MAX_BATTERY_AUTO_POWER_SAVE_THRESHOLD)

    private val featureKeys = listOf(
        PlatformSettingsKeys.KEY_KEEP_REFRESH_RATE_IN_POWER_SAVE,
        PlatformSettingsKeys.KEY_KEEP_HAPTIC_FEEDBACK_IN_POWER_SAVE,
        PlatformSettingsKeys.KEY_KEEP_SYSTEM_SOUNDS_IN_POWER_SAVE,
        PlatformSettingsKeys.KEY_KEEP_WAKEUP_GESTURES_IN_POWER_SAVE,
    )

    /** Preserve the effective legacy selection when editing one independent feature. */
    fun setPowerSaveFeature(key: String, enabled: Boolean) {
        require(key in featureKeys)
        val master = PlatformSettingsKeys.KEY_POWER_SAVE_OVERRIDE_MASTER
        val values = PowerSaveOverrideSelection.edit(
            Preferences.getBoolean(master, false), featureKeys.associateWith { Preferences.getBoolean(it, false) }, key, enabled,
        )
        publish(values + (master to true))
    }

    /** Older editors persisted this selection locally without acknowledging it for the hosts. */
    fun recoverUnpublishedSelection() {
        val acknowledged = RuntimeSettingsSignal.lastAcknowledged()?.values ?: return
        val local = Preferences.localSource() ?: return
        val master = PlatformSettingsKeys.KEY_POWER_SAVE_OVERRIDE_MASTER
        val keys = featureKeys + master
        if (keys.any(acknowledged::containsKey) || !local.getBoolean(master, false)) return
        publish(keys.associateWith { local.getBoolean(it, false) })
    }

    private fun publish(values: Map<String, Boolean>) {
        Preferences.commitRuntimeChanges(values) {
            values.forEach { (key, value) -> putBoolean(key, value) }
        }
    }
}
