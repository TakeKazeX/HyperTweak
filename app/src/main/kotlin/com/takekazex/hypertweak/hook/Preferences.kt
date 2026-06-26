package com.takekazex.hypertweak.hook

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

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
    const val KEY_LANGUAGE = "app_language"

    const val KEY_PAGE_SCALE = "page_scale"
    const val KEY_APP_SHORTCUTS = "app_shortcuts"
    const val KEY_APP_SHORTCUTS_ORDER = "app_shortcuts_order"
    const val KEY_DISABLE_SPATIAL_AUDIO = "disable_spatial_audio"
    const val KEY_FORCE_ADAPTIVE_ANC = "force_adaptive_anc"
    const val KEY_FCM_LIVE_ENABLED = "fcm_live_enabled"
    const val KEY_PENDING_RESTART_SCOPES = "pending_restart_scopes"
    const val KEY_LOG_LEVEL = "debug_log_level"
    private const val LEGACY_KEY_DEBUG_LOG = "debug_log"
    private const val KEY_DEBUG_LOG_PREFIX = "debug_log_p_"
    private const val KEY_LOG_SESSION = "debug_log_session"
    private const val MAX_DEBUG_LOG_LENGTH = 40_000

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

    @Synchronized
    fun initLocalCache(context: Context) {
        localCachePrefs = context.getSharedPreferences("hypertweak_cache", Context.MODE_PRIVATE)
    }

    @Synchronized
    private fun getLocalCache(): SharedPreferences? {
        return localCachePrefs
    }

    val isInitialized: Boolean
        get() = this::remotePrefs.isInitialized

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        if (!isInitialized) return default
        if (remotePrefs.contains(key)) {
            val value = remotePrefs.getBoolean(key, default)
            val cache = getLocalCache()
            if (cache != null && (!cache.contains(key) || cache.getBoolean(key, !value) != value)) {
                cache.edit { putBoolean(key, value) }
            }
            return value
        }
        return getLocalCache()?.getBoolean(key, default) ?: default
    }

    fun getInt(key: String, default: Int = 0): Int {
        if (!isInitialized) return default
        if (remotePrefs.contains(key)) {
            val value = remotePrefs.getInt(key, default)
            val cache = getLocalCache()
            if (cache != null && (!cache.contains(key) || cache.getInt(key, value - 1) != value)) {
                cache.edit { putInt(key, value) }
            }
            return value
        }
        return getLocalCache()?.getInt(key, default) ?: default
    }

    fun getFloat(key: String, default: Float = 1f): Float {
        if (!isInitialized) return default
        if (remotePrefs.contains(key)) {
            val value = remotePrefs.getFloat(key, default)
            val cache = getLocalCache()
            if (cache != null && (!cache.contains(key) || cache.getFloat(key, value - 1f) != value)) {
                cache.edit { putFloat(key, value) }
            }
            return value
        }
        return getLocalCache()?.getFloat(key, default) ?: default
    }

    fun putBoolean(key: String, value: Boolean) {
        if (isInitialized) {
            remotePrefs.edit { putBoolean(key, value) }
        }
    }

    fun putInt(key: String, value: Int) {
        if (isInitialized) {
            remotePrefs.edit { putInt(key, value) }
        }
    }

    fun putFloat(key: String, value: Float) {
        if (isInitialized) {
            remotePrefs.edit { putFloat(key, value) }
        }
    }

    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String> {
        if (!isInitialized) return default
        if (remotePrefs.contains(key)) {
            val value = remotePrefs.getStringSet(key, default) ?: default
            val cache = getLocalCache()
            if (cache != null && (!cache.contains(key) || cache.getStringSet(key, emptySet()) != value)) {
                cache.edit { putStringSet(key, value) }
            }
            return value
        }
        return getLocalCache()?.getStringSet(key, default) ?: default
    }

    fun putStringSet(key: String, value: Set<String>) {
        if (isInitialized) {
            remotePrefs.edit { putStringSet(key, value) }
        }
    }

    fun getString(key: String, default: String = ""): String {
        if (!isInitialized) return default
        if (remotePrefs.contains(key)) {
            val value = remotePrefs.getString(key, default) ?: default
            val cache = getLocalCache()
            if (cache != null && (!cache.contains(key) || cache.getString(key, "") != value)) {
                cache.edit { putString(key, value) }
            }
            return value
        }
        return getLocalCache()?.getString(key, default) ?: default
    }

    fun putString(key: String, value: String) {
        if (isInitialized) {
            remotePrefs.edit { putString(key, value) }
        }
    }

    @Synchronized
    fun appendDebugLog(processTag: String, line: String) {
        appendDebugLogs(processTag, listOf(line))
    }

    @Synchronized
    fun appendDebugLogs(processTag: String, lines: List<String>) {
        if (!isInitialized) return
        if (lines.isEmpty()) return
        val key = debugLogKeyFor(processTag)
        val old = remotePrefs.getString(key, "").orEmpty()
        val appended = lines.joinToString("\n")
        var next = if (old.isEmpty()) appended else "$old\n$appended"
        if (next.length > MAX_DEBUG_LOG_LENGTH) {
            next = next.takeLast(MAX_DEBUG_LOG_LENGTH)
            val firstNewLine = next.indexOf('\n')
            if (firstNewLine >= 0 && firstNewLine < next.lastIndex) {
                next = next.substring(firstNewLine + 1)
            }
        }
        remotePrefs.edit(commit = true) { putString(key, next) }
    }

    fun getDebugLog(): String {
        if (!isInitialized) return ""
        val blocks = remotePrefs.all.entries
            .filter { it.key.startsWith(KEY_DEBUG_LOG_PREFIX) || it.key == LEGACY_KEY_DEBUG_LOG }
            .mapNotNull { (it.value as? String)?.takeIf(String::isNotEmpty) }
        return blocks.joinToString("\n")
    }

    fun clearDebugLog() {
        if (!isInitialized) return
        val keys = remotePrefs.all.keys
            .filter { it.startsWith(KEY_DEBUG_LOG_PREFIX) || it == LEGACY_KEY_DEBUG_LOG }
        if (keys.isEmpty()) return
        remotePrefs.edit(commit = true) { keys.forEach(::remove) }
    }

    /**
     * Clears all debug logs when the runtime session changes (app update / reinstall / reboot),
     * so records from different sessions are not mixed together. No-op when the token is unchanged.
     */
    @Synchronized
    fun rotateLogSessionIfNeeded(token: String) {
        if (!isInitialized) return
        if (remotePrefs.getString(KEY_LOG_SESSION, null) == token) return
        val keys = remotePrefs.all.keys
            .filter { it.startsWith(KEY_DEBUG_LOG_PREFIX) || it == LEGACY_KEY_DEBUG_LOG }
        remotePrefs.edit(commit = true) {
            keys.forEach(::remove)
            putString(KEY_LOG_SESSION, token)
        }
    }

    private fun debugLogKeyFor(processTag: String): String {
        val sanitized = processTag.ifBlank { "unknown" }
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
        return "$KEY_DEBUG_LOG_PREFIX$sanitized"
    }
}
