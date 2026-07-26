package com.takekazex.hypertweak.hook

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.concurrent.Executors

object Preferences {
    const val NAME = "hypertweak_settings"
    const val DEFAULT_SEED_COLOR = 0
    const val DEFAULT_IMMEDIATE_MONET_REFRESH = true

    // Key names
    const val KEY_AOD_FULLSCREEN = "support_aod_fullscreen"
    const val KEY_REMOVE_GMS_RESTRICTION = "remove_gms_restriction"
    const val KEY_HIDE_FINGERPRINT = "hide_fingerprint"
    const val KEY_HIDE_LOCKSCREEN_STATUS_BAR = "hide_lockscreen_status_bar"
    const val KEY_HIDE_GESTURE_BAR = "hide_gesture_bar"
    const val KEY_GESTURE_BAR_RAISE_LAYOUT = "gesture_bar_raise_layout"
    const val KEY_GESTURE_BAR_ACTIONS_ENABLED = "gesture_bar_actions_enabled"
    const val KEY_GESTURE_BAR_LONG_PRESS_ACTION = "gesture_bar_long_press_action"
    const val KEY_GESTURE_BAR_DOUBLE_TAP_ACTION = "gesture_bar_double_tap_action"
    const val KEY_SHOW_IN_SETTINGS = "show_in_settings"
    const val KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon"
    const val KEY_SLIDER_SHOW_PERCENTAGE = "systemui_control_center_slider_show_percentage_enabled"
    const val KEY_SLIDER_SAME_PERCENTAGE_STYLE = "systemui_control_center_slider_same_percentage_style_enabled"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_USE_MONET = "theme_use_monet"
    const val KEY_SEED_COLOR = "theme_seed_color"
    const val KEY_THEME_PALETTE_STYLE = "theme_palette_style"
    const val KEY_PURE_BLACK_DARK_THEME = "pure_black_dark_theme"
    const val KEY_USE_FLOATING_BOTTOM_BAR = "use_floating_bottom_bar"
    const val KEY_FLOATING_BAR_STYLE = "floating_bar_style"
    const val KEY_PREDICTIVE_BACK_STYLE = "predictive_back_style"
    const val KEY_PREDICTIVE_BACK_FOLLOW_GESTURE = "predictive_back_follow_gesture"
    const val KEY_MIUI_BACK_GESTURE_HOOK = "miui_back_gesture_hook"
    const val KEY_CROSS_TASK_WALLPAPER_BACKGROUND = "cross_task_wallpaper_background"

    // AOSP back gesture, ported from MiuiBackGestureHook v0.8.1.
    /** Per-app predictive-back opt-in, read in the system server. */
    const val KEY_AOSP_BACK_OPT_IN_PACKAGES = "aosp_back_opt_in_packages"
    const val KEY_AOSP_BACK_HYPEROS_INDICATOR = "aosp_back_hyperos_indicator"
    const val KEY_AOSP_BACK_HYPEROS_HAPTICS = "aosp_back_hyperos_haptics"
    const val KEY_AOSP_BACK_HYPEROS_HAPTICS_ENHANCED = "aosp_back_hyperos_haptics_enhanced"
    const val KEY_AOSP_BACK_SLIDE_ANIMATION = "aosp_back_slide_animation"

    /**
     * Launcher-side hook route. Only Launcher 7 exposes the `com.miui.home` Java classes the
     * predictive return-home animation hooks, so this defaults off on Launcher 8 and newer.
     * See [KEY_AOSP_BACK_MIUI_HOME_HOOKS_USER_SET].
     */
    const val KEY_AOSP_BACK_MIUI_HOME_HOOKS = "aosp_back_miui_home_hooks"
    const val KEY_AOSP_BACK_MIUI_HOME_HOOKS_USER_SET = "aosp_back_miui_home_hooks_user_set"

    /** Cached `com.miui.home` version, so hook processes can gate without a PackageManager. */
    const val KEY_LAUNCHER_MAJOR = "launcher_version_major"
    const val KEY_LAUNCHER_VERSION_NAME = "launcher_version_name"
    const val KEY_ALLOW_LANDSCAPE = "allow_landscape"
    const val KEY_UNLOCK_PASSKEY = "unlock_passkey"

    // AOSP restore.
    /**
     * Hands package installs back to the AOSP installer. Relaxes MIUI install verification while
     * the installer-selection methods run; see `AospPackageInstallerHooker`.
     */
    const val KEY_AOSP_PACKAGE_INSTALLER = "aosp_package_installer"

    /** Blocks MIUI's global-actions plugin so SystemUI falls back to the AOSP power menu. */
    const val KEY_AOSP_POWER_MENU = "aosp_power_menu"

    /** Blocks MIUI's volume-dialog plugin so SystemUI falls back to the AOSP volume panel. */
    const val KEY_AOSP_VOLUME_PANEL = "aosp_volume_panel"
    const val KEY_LANGUAGE = "app_language"

    const val KEY_PAGE_SCALE = "page_scale"
    const val KEY_APP_SHORTCUTS = "app_shortcuts"
    const val KEY_APP_SHORTCUTS_ORDER = "app_shortcuts_order"
    const val KEY_DISABLE_SPATIAL_AUDIO = "disable_spatial_audio"
    const val KEY_FORCE_ADAPTIVE_ANC = "force_adaptive_anc"
    const val KEY_FCM_LIVE_ENABLED = "fcm_live_enabled"
    const val KEY_IMMEDIATE_MONET_REFRESH = "immediate_monet_refresh"
    const val KEY_PENDING_RESTART_SCOPES = "pending_restart_scopes"
    const val KEY_LOG_LEVEL = "debug_log_level"
    const val KEY_RECORD_LOGS = "record_logs"
    const val KEY_AOSP_BACK_LOGS = "aosp_back_logs"
    private const val LEGACY_KEY_DEBUG_LOG = "debug_log"
    private const val KEY_DEBUG_LOG_PREFIX = "debug_log_p_"
    private const val KEY_LOG_SESSION = "debug_log_session"
    private const val MAX_DEBUG_LOG_LENGTH = 40_000

    private lateinit var remotePrefs: SharedPreferences
    private var localSourcePrefs: SharedPreferences? = null
    private var localCachePrefs: SharedPreferences? = null
    private var isLocalOnly = false
    private val serializedWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "HyperTweak-Prefs").apply { isDaemon = true } }

    fun init(prefs: SharedPreferences, useLocalOnly: Boolean = false) {
        if (useLocalOnly) {
            localSourcePrefs = prefs
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

    fun useLocalBackend() {
        localSourcePrefs?.let { remotePrefs = it; isLocalOnly = true }
    }

    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        if (!isInitialized) return
        val local = localSourcePrefs
        runCatching { local?.edit { block() } }
        if (isLocalOnly || local === remotePrefs) return
        serializedWriter.execute {
            runCatching { remotePrefs.edit { block() } }
                .onFailure { useLocalBackend() }
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
        try {
        if (remotePrefs.contains(key)) {
            val value = remotePrefs.getBoolean(key, default)
            val cache = getLocalCache()
            if (cache != null && (!cache.contains(key) || cache.getBoolean(key, !value) != value)) {
                cache.edit { putBoolean(key, value) }
            }
            return value
        }
        return getLocalCache()?.getBoolean(key, default) ?: default
        } catch (_: Throwable) { useLocalBackend(); return localSourcePrefs?.getBoolean(key, default) ?: getLocalCache()?.getBoolean(key, default) ?: default }
    }

    fun getInt(key: String, default: Int = 0): Int {
        if (!isInitialized) return default
        try {
        if (remotePrefs.contains(key)) {
            val value = remotePrefs.getInt(key, default)
            val cache = getLocalCache()
            if (cache != null && (!cache.contains(key) || cache.getInt(key, value - 1) != value)) {
                cache.edit { putInt(key, value) }
            }
            return value
        }
        return getLocalCache()?.getInt(key, default) ?: default
        } catch (_: Throwable) { useLocalBackend(); return localSourcePrefs?.getInt(key, default) ?: getLocalCache()?.getInt(key, default) ?: default }
    }

    fun getFloat(key: String, default: Float = 1f): Float {
        if (!isInitialized) return default
        try {
        if (remotePrefs.contains(key)) {
            val value = remotePrefs.getFloat(key, default)
            val cache = getLocalCache()
            if (cache != null && (!cache.contains(key) || cache.getFloat(key, value - 1f) != value)) {
                cache.edit { putFloat(key, value) }
            }
            return value
        }
        return getLocalCache()?.getFloat(key, default) ?: default
        } catch (_: Throwable) { useLocalBackend(); return localSourcePrefs?.getFloat(key, default) ?: getLocalCache()?.getFloat(key, default) ?: default }
    }

    fun putBoolean(key: String, value: Boolean) {
        write { putBoolean(key, value) }
    }

    fun putInt(key: String, value: Int) {
        write { putInt(key, value) }
    }

    fun putFloat(key: String, value: Float) {
        write { putFloat(key, value) }
    }

    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String> {
        if (!isInitialized) return default
        try {
        if (remotePrefs.contains(key)) {
            val value = remotePrefs.getStringSet(key, default) ?: default
            val cache = getLocalCache()
            if (cache != null && (!cache.contains(key) || cache.getStringSet(key, emptySet()) != value)) {
                cache.edit { putStringSet(key, value) }
            }
            return value
        }
        return getLocalCache()?.getStringSet(key, default) ?: default
        } catch (_: Throwable) { useLocalBackend(); return localSourcePrefs?.getStringSet(key, default) ?: getLocalCache()?.getStringSet(key, default) ?: default }
    }

    fun putStringSet(key: String, value: Set<String>) {
        write { putStringSet(key, value) }
    }

    fun getString(key: String, default: String = ""): String {
        if (!isInitialized) return default
        try {
        if (remotePrefs.contains(key)) {
            val value = remotePrefs.getString(key, default) ?: default
            val cache = getLocalCache()
            if (cache != null && (!cache.contains(key) || cache.getString(key, "") != value)) {
                cache.edit { putString(key, value) }
            }
            return value
        }
        return getLocalCache()?.getString(key, default) ?: default
        } catch (_: Throwable) { useLocalBackend(); return localSourcePrefs?.getString(key, default) ?: getLocalCache()?.getString(key, default) ?: default }
    }

    fun putString(key: String, value: String) {
        write { putString(key, value) }
    }

    @Synchronized
    fun appendDebugLog(processTag: String, line: String) {
        appendDebugLogs(processTag, listOf(line))
    }

    @Synchronized
    fun appendDebugLogs(processTag: String, lines: List<String>) {
        if (!isInitialized) return
        if (!getBoolean(KEY_RECORD_LOGS, true)) return
        if (lines.isEmpty()) return
        val key = debugLogKeyFor(processTag)
        val local = localSourcePrefs
        val old = runCatching { remotePrefs.getString(key, "") }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { local?.getString(key, "") }.getOrNull().orEmpty()
        val appended = lines.joinToString("\n")
        var next = if (old.isEmpty()) appended else "$old\n$appended"
        if (next.length > MAX_DEBUG_LOG_LENGTH) {
            next = next.takeLast(MAX_DEBUG_LOG_LENGTH)
            val firstNewLine = next.indexOf('\n')
            if (firstNewLine >= 0 && firstNewLine < next.lastIndex) {
                next = next.substring(firstNewLine + 1)
            }
        }
        runCatching { local?.edit(commit = true) { putString(key, next) } }
        if (!isLocalOnly) runCatching { remotePrefs.edit(commit = true) { putString(key, next) } }
    }

    fun getDebugLog(): String {
        if (!isInitialized) return ""
        val blocks = runCatching { remotePrefs.all.entries }.getOrElse { emptySet() }
            .filter { it.key.startsWith(KEY_DEBUG_LOG_PREFIX) || it.key == LEGACY_KEY_DEBUG_LOG }
            .mapNotNull { (it.value as? String)?.takeIf(String::isNotEmpty) }
        if (blocks.isNotEmpty()) return blocks.joinToString("\n")
        return runCatching {
            localSourcePrefs?.all.orEmpty().entries
                .filter { it.key.startsWith(KEY_DEBUG_LOG_PREFIX) || it.key == LEGACY_KEY_DEBUG_LOG }
                .mapNotNull { (it.value as? String)?.takeIf(String::isNotEmpty) }
                .joinToString("\n")
        }.getOrDefault("")
    }

    fun clearDebugLog() {
        if (!isInitialized) return
        val keys = (runCatching { remotePrefs.all.keys }.getOrElse { emptySet() } +
            runCatching { localSourcePrefs?.all.orEmpty().keys }.getOrElse { emptySet() })
            .filter { it.startsWith(KEY_DEBUG_LOG_PREFIX) || it == LEGACY_KEY_DEBUG_LOG }
        if (keys.isEmpty()) return
        runCatching { localSourcePrefs?.edit(commit = true) { keys.forEach(::remove) } }
        runCatching { remotePrefs.edit(commit = true) { keys.forEach(::remove) } }
    }

    /**
     * Clears all debug logs when the runtime session changes (app update / reinstall / reboot),
     * so records from different sessions are not mixed together. No-op when the token is unchanged.
     */
    @Synchronized
    fun rotateLogSessionIfNeeded(token: String) {
        if (!isInitialized) return
        val currentToken = runCatching { remotePrefs.getString(KEY_LOG_SESSION, null) }.getOrNull()
            ?: runCatching { localSourcePrefs?.getString(KEY_LOG_SESSION, null) }.getOrNull()
        if (currentToken == token) return
        val keys = (runCatching { remotePrefs.all.keys }.getOrElse { emptySet() } +
            runCatching { localSourcePrefs?.all.orEmpty().keys }.getOrElse { emptySet() })
            .filter { it.startsWith(KEY_DEBUG_LOG_PREFIX) || it == LEGACY_KEY_DEBUG_LOG }
        localSourcePrefs?.edit(commit = true) {
            keys.forEach(::remove)
            putString(KEY_LOG_SESSION, token)
        }
        if (!isLocalOnly) runCatching { remotePrefs.edit(commit = true) {
            keys.forEach(::remove)
            putString(KEY_LOG_SESSION, token)
        } }
    }

    private fun debugLogKeyFor(processTag: String): String {
        val sanitized = processTag.ifBlank { "unknown" }
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
        return "$KEY_DEBUG_LOG_PREFIX$sanitized"
    }
}
