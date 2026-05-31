package com.takekazex.hypertweak.hook

import android.content.SharedPreferences

object Preferences {
    const val NAME = "hypertweak_settings"

    // Key names
    const val KEY_AOD_FULLSCREEN = "support_aod_fullscreen"
    const val KEY_REMOVE_GMS_RESTRICTION = "remove_gms_restriction"
    const val KEY_HIDE_FINGERPRINT = "hide_fingerprint"
    const val KEY_HIDE_GESTURE_BAR = "hide_gesture_bar"
    const val KEY_GESTURE_BAR_RAISE_LAYOUT = "gesture_bar_raise_layout"
    const val KEY_SHOW_IN_SETTINGS = "show_in_settings"
    const val KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon"
    const val KEY_SLIDER_SHOW_PERCENTAGE = "systemui_control_center_slider_show_percentage_enabled"
    const val KEY_SLIDER_SAME_PERCENTAGE_STYLE = "systemui_control_center_slider_same_percentage_style_enabled"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_USE_MONET = "theme_use_monet"
    const val KEY_SEED_COLOR = "theme_seed_color"
    const val KEY_USE_FLOATING_BOTTOM_BAR = "use_floating_bottom_bar"
    const val KEY_FLOATING_BAR_STYLE = "floating_bar_style"
    const val KEY_PREDICTIVE_BACK_STYLE = "predictive_back_style"
    const val KEY_PREDICTIVE_BACK_FOLLOW_GESTURE = "predictive_back_follow_gesture"
    const val KEY_ALLOW_LANDSCAPE = "allow_landscape"
    const val KEY_UNLOCK_PASSKEY = "unlock_passkey"

    const val KEY_PAGE_SCALE = "page_scale"

    private lateinit var remotePrefs: SharedPreferences
    private var localCachePrefs: SharedPreferences? = null
    private var isLocalOnly = false

    fun init(prefs: SharedPreferences, useLocalOnly: Boolean = false) {
        if (useLocalOnly) {
            // Only apply local prefs as fallback if remote prefs haven't been set yet
            if (!this::remotePrefs.isInitialized || isLocalOnly) {
                remotePrefs = prefs
                isLocalOnly = true
            }
        } else {
            // Remote prefs always win and can upgrade a local-only instance
            remotePrefs = prefs
            isLocalOnly = false
        }
    }

    private fun getLocalCache(): SharedPreferences? {
        if (localCachePrefs != null) return localCachePrefs
        val app = runCatching {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentApplication").invoke(null) as? android.content.Context
        }.getOrNull()
        if (app != null) {
            localCachePrefs = app.getSharedPreferences("hypertweak_cache", android.content.Context.MODE_PRIVATE)
        }
        return localCachePrefs
    }

    val isInitialized: Boolean
        get() = this::remotePrefs.isInitialized

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        if (!isInitialized) return default
        if (remotePrefs.contains(key)) {
            val value = remotePrefs.getBoolean(key, default)
            getLocalCache()?.edit()?.putBoolean(key, value)?.apply()
            return value
        }
        return getLocalCache()?.getBoolean(key, default) ?: default
    }

    fun getInt(key: String, default: Int = 0): Int {
        if (!isInitialized) return default
        if (remotePrefs.contains(key)) {
            val value = remotePrefs.getInt(key, default)
            getLocalCache()?.edit()?.putInt(key, value)?.apply()
            return value
        }
        return getLocalCache()?.getInt(key, default) ?: default
    }

    fun getFloat(key: String, default: Float = 1f): Float {
        if (!isInitialized) return default
        if (remotePrefs.contains(key)) {
            val value = remotePrefs.getFloat(key, default)
            getLocalCache()?.edit()?.putFloat(key, value)?.apply()
            return value
        }
        return getLocalCache()?.getFloat(key, default) ?: default
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

    fun putFloat(key: String, value: Float) {
        if (isInitialized) {
            remotePrefs.edit().putFloat(key, value).apply()
        }
    }
}
