package com.takekazex.hypertweak.hook

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.Executors

object Preferences {
    const val NAME = "hypertweak_settings"
    const val DEFAULT_SEED_COLOR = 0
    const val DEFAULT_IMMEDIATE_MONET_REFRESH = true

    // Key names
    const val KEY_AOD_FULLSCREEN = "support_aod_fullscreen"
    const val KEY_REMOVE_GMS_RESTRICTION = "remove_gms_restriction"

    /**
     * Unlocks Nearby Share (Quick Share) on CN Google Play services by overriding the
     * `sharing_supports_latchsky` phenotype flag to true in GMS's CE `phenotype.db`.
     * GMS must be in the Xposed scope (requested when the switch is turned on).
     */
    const val KEY_QUICK_SHARE_ENABLED = "quick_share_enabled"
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

    // AOSP back gesture, ported through MiuiBackGestureHook v0.8.5 + git a5f1ae5.
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

    /** Re-derives keyguard trust state from TrustManagerService when SystemUI's cache goes stale. */
    const val KEY_EXTEND_UNLOCK_FIX = "extend_unlock_fix"

    /** Lets AOSP's clipboard overlay editor appear for real copies, not just CTS. */
    const val KEY_AOSP_CLIPBOARD_EDITOR = "aosp_clipboard_editor"

    /** Security Center entry points into Settings' AOSP app screens. */
    const val KEY_AOSP_APP_INFO_ENTRY = "aosp_app_info_entry"
    const val KEY_AOSP_APP_MANAGER_ENTRY = "aosp_app_manager_entry"

    /** Restores AOSP's full-screen IME navigation bar. */
    const val KEY_AOSP_IME_ENABLED = "aosp_ime_fullscreen"

    /** Input methods the AOSP IME hooks apply to; also drives the dynamic scope request. */
    const val KEY_AOSP_IME_PACKAGES = "aosp_ime_packages"
    const val KEY_AOSP_IME_NAV_BAR_START = "aosp_ime_nav_bar_start"
    const val KEY_AOSP_IME_NAV_BAR_END = "aosp_ime_nav_bar_end"

    /** Lists every enabled input method in MIUI's keyboard switcher. Unverified off-device. */
    const val KEY_AOSP_IME_MIUI_IME_LIST = "aosp_ime_miui_ime_list"
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

    // Status-bar icon tuner, ported from Hyper Helper's icon tuner (see the reverse-engineering
    // workspace, cache/xiaomihelper-2bfd4873a4138764). Each switch is read by a SystemUI hook at
    // hook-install time and requires a SystemUI restart; the page keeps its own state like
    // AospRestorePage, so these keys are deliberately absent from TWEAK_RESTART_SCOPES.
    const val KEY_ICON_HIDE_CELLULAR_ACTIVITY = "icon_hide_cellular_activity"
    const val KEY_ICON_HIDE_CELLULAR_TYPE = "icon_hide_cellular_type"
    const val KEY_ICON_HIDE_CELLULAR_ROAM = "icon_hide_cellular_roam"
    const val KEY_ICON_HIDE_CELLULAR_ROAM_GLOBAL = "icon_hide_cellular_roam_global"
    const val KEY_ICON_HIDE_CELLULAR_SMALL_ROAM = "icon_hide_cellular_small_roam"
    const val KEY_ICON_HIDE_CELLULAR_VOWIFI = "icon_hide_cellular_vowifi"
    const val KEY_ICON_HIDE_CELLULAR_VOLTE = "icon_hide_cellular_volte"
    const val KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE = "icon_hide_cellular_volte_no_service"
    const val KEY_ICON_HIDE_CELLULAR_SPEECH_HD = "icon_hide_cellular_speech_hd"
    const val KEY_ICON_HIDE_WIFI_ACTIVITY = "icon_hide_wifi_activity"
    const val KEY_ICON_HIDE_WIFI_TYPE = "icon_hide_wifi_type"
    const val KEY_ICON_HIDE_WIFI_UNAVAILABLE = "icon_hide_wifi_unavailable"
    const val KEY_ICON_WIFI_ACTIVITY_RIGHT = "icon_wifi_activity_right"

    // Hide the cellular icon of the non-default SIM when multiple SIMs are active.
    const val KEY_ICON_HIDE_NON_DEFAULT_SIM = "icon_hide_non_default_sim"
    const val KEY_ICON_HIDE_SIM_AUTO = "icon_hide_sim_auto"

    // Stacked mobile signal (custom SVG signal icon).
    const val KEY_ICON_STACKED_ENABLED = "icon_stacked_enabled"
    const val KEY_ICON_STACKED_SVG_SINGLE = "icon_stacked_svg_single"
    const val KEY_ICON_STACKED_SVG_STACKED = "icon_stacked_svg_stacked"
    const val KEY_ICON_STACKED_SCALE = "icon_stacked_scale"
    const val KEY_ICON_STACKED_PADDING_START = "icon_stacked_padding_start"
    const val KEY_ICON_STACKED_PADDING_END = "icon_stacked_padding_end"
    const val KEY_ICON_STACKED_ALPHA_FG = "icon_stacked_alpha_fg"
    const val KEY_ICON_STACKED_ALPHA_BG = "icon_stacked_alpha_bg"
    const val KEY_ICON_STACKED_ALPHA_ERROR = "icon_stacked_alpha_error"
    const val KEY_ICON_STACKED_TYPE_SIZE = "icon_stacked_type_size"
    const val KEY_ICON_STACKED_TYPE_WEIGHT = "icon_stacked_type_weight"
    const val KEY_ICON_STACKED_SHOW_SINGLE = "icon_stacked_show_single"
    const val KEY_ICON_STACKED_SHOW_STACKED = "icon_stacked_show_stacked"
    const val KEY_ICON_STACKED_SHOW_ROAMING = "icon_stacked_show_roaming"

    // Compound icon feature toggles (gates the compound_* slots in IconManagerHooker).
    const val KEY_ICON_COMPOUND_ALARM = "icon_compound_alarm"
    const val KEY_ICON_COMPOUND_ZEN = "icon_compound_zen"
    const val KEY_ICON_COMPOUND_LOCATION = "icon_compound_location"
    const val KEY_ICON_COMPOUND_VOLUME = "icon_compound_volume"

    // Slot show/hide modes (0 = follow system, 1 = hide, 2 = status bar only, 3 = control center
    // only, 4 = show everywhere). Keys are generated by [slotKey].
    const val KEY_ICON_EXT_BLOCKED = "icon_ext_blocked"

    // Ignore the system's own icon hiding.
    const val KEY_ICON_IGNORE_SYS_HIDE = "icon_ignore_sys_hide"
    const val KEY_ICON_HIDE_PRIVACY = "icon_hide_privacy"

    /** Status-bar slot preference key for [slot]; shared by the hooks and the settings UI. */
    fun slotKey(slot: String): String = "icon_tuner_slot_$slot"
    private const val LEGACY_KEY_DEBUG_LOG = "debug_log"
    private const val KEY_DEBUG_LOG_PREFIX = "debug_log_p_"
    private const val KEY_LOG_SESSION = "debug_log_session"
    private const val MAX_DEBUG_LOG_LENGTH = 40_000

    private lateinit var remotePrefs: SharedPreferences
    private var localSourcePrefs: SharedPreferences? = null
    @Volatile
    private var localCachePrefs: SharedPreferences? = null
    private var isLocalOnly = false
    private val serializedWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "HyperTweak-Prefs").apply { isDaemon = true } }

    /**
     * Bumped on every full reset ([clearAllSettings]). Hooked processes keep a local cache
     * (`hypertweak_cache.xml` in each app's data dir) that the module cannot reach; the
     * epoch stored in the authoritative remote prefs invalidates those caches so a reset
     * actually takes effect instead of serving stale values.
     */
    private const val KEY_PREFS_EPOCH = "prefs_epoch"
    private const val INITIAL_EPOCH = 0L

    /**
     * Wipes every setting in every storage location the module uses:
     * - the module's own `hypertweak_settings` / `hypertweak_cache` files (deleted on
     *   uninstall anyway);
     * - the LSPosed daemon's remote copy (`modules_config.db`), which survives uninstall
     *   and is why settings come back after a reinstall.
     * The epoch bump makes hooked processes fall back to defaults instead of their local
     * caches. After this call every setting is at its default; hook processes pick the new
     * state up on their next read without a reboot.
     */
    fun clearAllSettings() {
        synchronized(logLock) {
            if (!isInitialized) return
            val epoch = runCatching { remotePrefs.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }.getOrDefault(INITIAL_EPOCH) + 1
            runCatching {
                remotePrefs.edit().clear().putLong(KEY_PREFS_EPOCH, epoch).commit()
            }
            runCatching { localSourcePrefs?.edit(commit = true) { clear() } }
            val cache = getLocalCache()
            runCatching { cache?.edit(commit = true) { clear().putLong(KEY_PREFS_EPOCH, epoch) } }
        }
    }

    /** True when the remote copy still holds settings (i.e. a previous install left config behind). */
    fun hasRemoteConfig(): Boolean {
        if (!isInitialized) return false
        return runCatching { remotePrefs.all.keys.any { it != KEY_PREFS_EPOCH } }.getOrDefault(false)
    }

    /**
     * False when the remote prefs were wiped after this process last cached values, so the
     * cache must not be trusted and reads fall back to defaults.
     */
    private fun cacheEpochMatchesRemote(): Boolean {
        val cache = getLocalCache() ?: return true
        val remoteEpoch = runCatching { remotePrefs.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }.getOrDefault(INITIAL_EPOCH)
        val cacheEpoch = runCatching { cache.getLong(KEY_PREFS_EPOCH, INITIAL_EPOCH) }.getOrDefault(INITIAL_EPOCH)
        return remoteEpoch == cacheEpoch
    }

    /** Serializes debug-log writes so log I/O never shares the object monitor with preference reads. */
    private val logLock = Any()

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
                .onFailure { DebugLog.w("Preferences", "remote pref write failed; retrying on next write", it) }
        }
    }

    fun initLocalCache(context: Context) {
        localCachePrefs = context.getSharedPreferences("hypertweak_cache", Context.MODE_PRIVATE)
    }

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
        if (cacheEpochMatchesRemote()) {
            return getLocalCache()?.getBoolean(key, default) ?: default
        }
        return default
        } catch (_: Throwable) { return localSourcePrefs?.getBoolean(key, default) ?: getLocalCache()?.getBoolean(key, default) ?: default }
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
        if (cacheEpochMatchesRemote()) {
            return getLocalCache()?.getInt(key, default) ?: default
        }
        return default
        } catch (_: Throwable) { return localSourcePrefs?.getInt(key, default) ?: getLocalCache()?.getInt(key, default) ?: default }
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
        if (cacheEpochMatchesRemote()) {
            return getLocalCache()?.getFloat(key, default) ?: default
        }
        return default
        } catch (_: Throwable) { return localSourcePrefs?.getFloat(key, default) ?: getLocalCache()?.getFloat(key, default) ?: default }
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
        if (cacheEpochMatchesRemote()) {
            return getLocalCache()?.getStringSet(key, default) ?: default
        }
        return default
        } catch (_: Throwable) { return localSourcePrefs?.getStringSet(key, default) ?: getLocalCache()?.getStringSet(key, default) ?: default }
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
        if (cacheEpochMatchesRemote()) {
            return getLocalCache()?.getString(key, default) ?: default
        }
        return default
        } catch (_: Throwable) { return localSourcePrefs?.getString(key, default) ?: getLocalCache()?.getString(key, default) ?: default }
    }

    fun putString(key: String, value: String) {
        write { putString(key, value) }
    }

    fun appendDebugLog(processTag: String, line: String) {
        synchronized(logLock) {
            appendDebugLogs(processTag, listOf(line))
        }
    }

    fun appendDebugLogs(processTag: String, lines: List<String>) {
        synchronized(logLock) {
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
        synchronized(logLock) {
            if (!isInitialized) return
            val keys = (runCatching { remotePrefs.all.keys }.getOrElse { emptySet() } +
                runCatching { localSourcePrefs?.all.orEmpty().keys }.getOrElse { emptySet() })
                .filter { it.startsWith(KEY_DEBUG_LOG_PREFIX) || it == LEGACY_KEY_DEBUG_LOG }
            if (keys.isEmpty()) return
            runCatching { localSourcePrefs?.edit(commit = true) { keys.forEach(::remove) } }
            runCatching { remotePrefs.edit(commit = true) { keys.forEach(::remove) } }
        }
    }

    /**
     * Clears all debug logs when the runtime session changes (app update / reinstall / reboot),
     * so records from different sessions are not mixed together. No-op when the token is unchanged.
     */
    fun rotateLogSessionIfNeeded(token: String) {
        synchronized(logLock) {
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
    }

    private fun debugLogKeyFor(processTag: String): String {
        val sanitized = processTag.ifBlank { "unknown" }
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
        return "$KEY_DEBUG_LOG_PREFIX$sanitized"
    }
}
