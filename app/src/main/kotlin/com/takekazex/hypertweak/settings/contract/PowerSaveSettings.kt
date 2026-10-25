package com.takekazex.hypertweak.settings.contract

internal interface PowerSaveSettings {
    fun setPowerSaveFeature(key: String, enabled: Boolean): Unit
    fun batteryAutoPowerSaveThreshold(): Int
    fun batteryAutoPowerSaveEnabled(): Boolean
    fun exitPowerSaveWhenCharging(): Boolean
    fun keepHapticFeedbackInPowerSave(): Boolean
    fun keepRefreshRateInPowerSave(): Boolean
    fun keepSystemSoundsInPowerSave(): Boolean
    fun keepWakeupGesturesInPowerSave(): Boolean
}
