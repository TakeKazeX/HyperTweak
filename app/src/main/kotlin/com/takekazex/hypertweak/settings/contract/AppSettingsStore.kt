package com.takekazex.hypertweak.settings.contract

/** App-side access to effective settings and the existing acknowledged writer. */
internal interface AppSettingsStore {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun getInt(key: String, defaultValue: Int): Int
    fun getFloat(key: String, defaultValue: Float): Float
    fun putBoolean(key: String, value: Boolean): Unit
    fun putInt(key: String, value: Int): Unit
    fun putFloat(key: String, value: Float): Unit
    fun flush(): Unit
    fun clearAllSettings(): Boolean
    fun hideFingerprintAodEnabled(): Boolean
    fun hideFingerprintLockscreenEnabled(): Boolean
    fun hideFingerprintAppAuthEnabled(): Boolean
    fun powerButtonAction(): Int
    fun setPowerButtonAction(action: Int): Unit
    fun contextualSearchLongPress(): Boolean
}
