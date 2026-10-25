package com.takekazex.hypertweak.app

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.settings.contract.AppSettingsStore

/** Binds the app store to the sole runtime storage and migration owner. */
internal object PreferencesAppSettings : AppSettingsStore {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = Preferences.getBoolean(key, defaultValue)
    override fun getInt(key: String, defaultValue: Int): Int = Preferences.getInt(key, defaultValue)
    override fun getFloat(key: String, defaultValue: Float): Float = Preferences.getFloat(key, defaultValue)
    override fun putBoolean(key: String, value: Boolean): Unit = Preferences.putBoolean(key, value)
    override fun putInt(key: String, value: Int): Unit = Preferences.putInt(key, value)
    override fun putFloat(key: String, value: Float): Unit = Preferences.putFloat(key, value)
    override fun flush(): Unit = Preferences.flush()
    override fun clearAllSettings(): Boolean = Preferences.clearAllSettings()
    override fun hideFingerprintAodEnabled(): Boolean = Preferences.hideFingerprintAodEnabled()
    override fun hideFingerprintLockscreenEnabled(): Boolean = Preferences.hideFingerprintLockscreenEnabled()
    override fun hideFingerprintAppAuthEnabled(): Boolean = Preferences.hideFingerprintAppAuthEnabled()
    override fun powerButtonAction(): Int = Preferences.powerButtonAction()
    override fun setPowerButtonAction(action: Int): Unit = Preferences.setPowerButtonAction(action)
    override fun contextualSearchLongPress(): Boolean = Preferences.contextualSearchLongPress()
}
