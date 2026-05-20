package com.takekazex.hypertweak.hook

import android.content.SharedPreferences

object Preferences {
    const val NAME = "hypertweak_settings"

    // Key names
    const val KEY_AOD_FULLSCREEN = "support_aod_fullscreen"
    const val KEY_REMOVE_GMS_RESTRICTION = "remove_gms_restriction"
    const val KEY_HIDE_FINGERPRINT = "hide_fingerprint"
    const val KEY_SHOW_IN_SETTINGS = "show_in_settings"
    const val KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon"

    private lateinit var remotePrefs: SharedPreferences

    fun init(prefs: SharedPreferences) {
        remotePrefs = prefs
    }

    val isInitialized: Boolean
        get() = this::remotePrefs.isInitialized

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        if (!isInitialized) return default
        return remotePrefs.getBoolean(key, default)
    }

    fun getInt(key: String, default: Int = 0): Int {
        if (!isInitialized) return default
        return remotePrefs.getInt(key, default)
    }

    fun putBoolean(key: String, value: Boolean) {
        if (isInitialized) {
            remotePrefs.edit().putBoolean(key, value).apply()
        }
    }

    fun putInt(key: String, value: Int) {
        if (isInitialized) {
            remotePrefs.edit().putInt(key, value).apply()
        }
    }
}
