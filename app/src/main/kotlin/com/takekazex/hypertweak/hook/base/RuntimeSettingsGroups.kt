package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.BackGestureSettingsSnapshot
import com.takekazex.hypertweak.hook.rules.systemui.SystemUiLiveSettings
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSettingsPreferencePolicy
import com.takekazex.hypertweak.dock.DockConfig

/** Ownership, process routing and resync authorization have one registry. */
internal object RuntimeSettingsGroups {
    const val ICONS = "systemui.icons"
    const val ISLANDS = "systemui.islands"
    const val FORCE_SCREENSHOT = "system.force_screenshot"
    const val SCREENSHOT = "screenshot.frame"
    const val BACK = "gesture.back"
    const val NATIVE = "launcher.native"
    const val DOCK = "launcher.dock"
    const val VOLUME = "system.volume"
    const val XMSF = "xmsf.focus"
    const val GESTURE_BAR = "system.gesture_bar"
    const val GESTURE_BAR_UI = "systemui.gesture_bar"
    /** Switches that decide whether a hooker is installed at all, not just how it behaves. */
    const val GATED_FEATURES = "features.installation"
    const val APP_DETAILS = "securitycenter.app_details"
    const val SECURITY_POPUPS = "securitycenter.sidebar_popups"
    const val SECURITY_DOCK = "securitycenter.dock"
    const val SECURITY_CHECKS = "securitycenter.checks"
    const val OFFLINE_CONFIG = "carrier.offline_config"
    const val XIAOAI_GESTURES = "xiaoai.gesture_input"
    const val SETTINGS_AI = "settings.hyperos_ai"
    const val SHARE_PANEL = "intentresolver.share_panel"
    /** Keyguard/AOD surface: SystemUI's keyguard battery row plus the AOD plugin rows. */
    const val AOD = "systemui.aod"
    /** Keyguard host surfaces whose policy is cached at install time (status bar, clock date, FOD). */
    const val KEYGUARD = "systemui.keyguard"
    /** Notification header layout, size and weather. */
    const val HEADER = "systemui.notification_header"
    /** Styling of already displayed notifications (clock seconds, Monet colour, absolute time, weight). */
    const val NOTIFICATION_TEXT = "systemui.notification_text"
    /** Media-card renderers in the host and in the control-center plugin. */
    const val MEDIA_CARD = "systemui.media_card"
    /** Control-center plugin sub-hooks whose presence a session used to decide at creation. */
    const val CONTROL_CENTER_PLUGIN = "systemui.control_center_plugin"
    /** Glass material tuner coefficients read from the resource/View funnels. */
    const val GLASS = "systemui.glass"
    /** Automatic power saving, its threshold and charging exit. */
    const val POWER_SAVE = "systemui.power_save"
    /** Lock-screen indication surfaces: bottom text, charging detail, fingerprint avoidance. */
    const val LOCKSCREEN = "systemui.lockscreen"
    const val AOD_CAPABILITY = "aod.capability"
    const val NOTIFICATION_ICONS = "systemui.notification_icons"
    const val NOTIFICATION_MINI_WINDOW = "systemui.notification_mini_window"
    /** Live floating-notification whitelist switch. */
    const val NOTIFICATION_WHITELIST = "systemui.notification_whitelist"
    const val FREEFORM_BLUR = "systemui.freeform_blur"
    const val REVISION = "runtime_settings_revision"
    private val islandKeys = setOf(
        Preferences.KEY_REMOVE_ISLAND_PLACEHOLDER, Preferences.KEY_THREE_ISLANDS, Preferences.KEY_LANDSCAPE_FULL_ISLANDS,
        Preferences.KEY_LANDSCAPE_MORE_ISLANDS, Preferences.KEY_CC_HIDE_SUPER_ISLAND,
        Preferences.KEY_ISLAND_NARROW_TIME_FONT, Preferences.KEY_ISLAND_LIVE_UPDATE_ICON,
        Preferences.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST,
        Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST
    )
    val backKeys = BackGestureSettingsSnapshot.KEYS.toSet() + setOf(
        "back_gesture_predictive_packages", "contextual_search_long_press",
        "gesture_trigger_height_percent", "gesture_trigger_position_percent", "aosp_gesture_hyperos_haptics"
    )
    private val aodKeys = setOf(
        Preferences.KEY_AOD_BATTERY_ICON_VISIBLE, Preferences.KEY_AOD_DUO_ENABLED,
        Preferences.KEY_AOD_BATTERY_PERCENT_VISIBLE, Preferences.KEY_AOD_PERCENT_POSITION,
        Preferences.KEY_AOD_DUO_CENTER_CONTENT
    )
    private val keyguardKeys = setOf(
        Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, Preferences.KEY_HIDE_LOCKSCREEN_DATE,
        Preferences.KEY_HIDE_FINGERPRINT, Preferences.KEY_HIDE_FINGERPRINT_AOD,
        Preferences.KEY_HIDE_FINGERPRINT_LOCKSCREEN, Preferences.KEY_HIDE_FINGERPRINT_APP_AUTH
    )
    /** Master and per-group power-save switches that decide whether the override hooker exists. */
    private val powerSaveKeys = setOf(
        Preferences.KEY_POWER_SAVE_OVERRIDE_MASTER,
        Preferences.KEY_KEEP_REFRESH_RATE_IN_POWER_SAVE,
        Preferences.KEY_KEEP_HAPTIC_FEEDBACK_IN_POWER_SAVE,
        Preferences.KEY_KEEP_SYSTEM_SOUNDS_IN_POWER_SAVE,
        Preferences.KEY_KEEP_WAKEUP_GESTURES_IN_POWER_SAVE,
    )
    private val watermarkKeys = setOf(
        Preferences.KEY_WM_UNLOCK_MASTER, Preferences.KEY_WM_LEICA, Preferences.KEY_WM_XIAOMI,
        Preferences.KEY_WM_REDMI, Preferences.KEY_WM_POCO, Preferences.KEY_WM_DISNEY1,
        Preferences.KEY_WM_DISNEY2, Preferences.KEY_WM_DISNEY3, Preferences.KEY_WM_VICTORIA,
        Preferences.KEY_WM_LCC, Preferences.KEY_WM_DOWNLOAD_ALL, Preferences.KEY_WM_CAMERA,
    )
    private val nativeKeys = setOf(Preferences.KEY_HIDE_RECENTS_CLEAR_BUTTON, Preferences.KEY_OPENED_FOLDER_COLUMNS,
        Preferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS, Preferences.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT,
        Preferences.KEY_NATIVE_RULE_REVISION, Preferences.KEY_RECENTS_CARD_HAPTIC, Preferences.KEY_FOLDER_STACK_PREVIEW, Preferences.KEY_ANDROID_WIDGET_STACK, Preferences.KEY_XIAOAI_GESTURE_OPTIONS, "prefs_epoch")
    val strictBooleanKeys = setOf(
        Preferences.KEY_SETTINGS_ONDEVICE_MODEL_ENTRY,
        Preferences.KEY_SETTINGS_GEMINI_ENTRY,
        Preferences.KEY_FORCE_SCREENSHOT,
        Preferences.KEY_SECURITY_CENTER_HIDE_DOLBY_POPUP,
        Preferences.KEY_SECURITY_CENTER_HIDE_GAME_POPUP,
        Preferences.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PINNED,
        Preferences.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PREDICTIONS,
        Preferences.KEY_XIAOAI_GESTURE_OPTIONS,
        Preferences.KEY_NOTIFICATION_WHITELIST_BYPASS,
        Preferences.KEY_SHARE_PANEL_SHOW_ALL,
        Preferences.KEY_APP_DETAILS_ICON_LAUNCH,
        Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK,
        Preferences.KEY_SECURITY_CENTER_SKIP_LOCAL_SU_CHECK,
        Preferences.KEY_SECURITY_CENTER_DISABLE_SAFEPAY_AUTO_SCAN,
        Preferences.KEY_SECURITY_CENTER_DISABLE_ROOT_SCAN_ITEM,
        Preferences.KEY_SECURITY_CENTER_DISABLE_USB_INSTALL_INTERCEPT,
        Preferences.KEY_SECURITY_CENTER_BYPASS_USB_INSTALL_NETWORK_VERIFY,
        Preferences.KEY_NETWORK_ASSISTANT_IGNORE_OFFLINE,
        Preferences.KEY_CONTACTS_BUSINESS_HALL_IGNORE_OFFLINE,
    )
    private data class Group(val targets: Set<String>, val owns: (String) -> Boolean)
    private val systemUi = setOf("com.android.systemui")
    private val groups = linkedMapOf(
        ICONS to Group(systemUi) { it != "prefs_epoch" && it !in islandKeys && IconSettingsPreferencePolicy.affectsIcons(it) },
        ISLANDS to Group(systemUi) { it in islandKeys },
        // Both AOD hosts: SystemUI's keyguard battery row and the AOD plugin (hosted by SystemUI
        // and by the standalone com.miui.aod process).
        AOD to Group(systemUi + "com.miui.aod") { it in aodKeys },
        AOD_CAPABILITY to Group(systemUi + setOf("com.miui.aod", "com.android.settings")) {
            it == Preferences.KEY_AOD_FULLSCREEN
        },
        NOTIFICATION_ICONS to Group(systemUi) { it == Preferences.KEY_NOTIFICATION_ICON_FILTER_MODE || it == Preferences.KEY_NOTIFICATION_MORE_SETTINGS },
        NOTIFICATION_MINI_WINDOW to Group(systemUi) { it == Preferences.KEY_NOTIFICATION_MINI_WINDOW },
        NOTIFICATION_WHITELIST to Group(systemUi) { it == Preferences.KEY_NOTIFICATION_WHITELIST_BYPASS },
        FREEFORM_BLUR to Group(systemUi) { it == Preferences.KEY_FREEFORM_BLUR_TRANSITION },
        KEYGUARD to Group(systemUi) { it in keyguardKeys },
        HEADER to Group(systemUi) { it in SystemUiLiveSettings.HEADER_KEYS },
        NOTIFICATION_TEXT to Group(systemUi) { it in SystemUiLiveSettings.NOTIFICATION_TEXT_KEYS },
        // Both media-card renderers: the host hooks in SystemUI and the control-center plugin's
        // device-icon child hook, which is chosen when a plugin session is created.
        MEDIA_CARD to Group(systemUi) { it in SystemUiLiveSettings.MEDIA_CARD_KEYS },
        // Control-center plugin sub-hooks (editor, resize, slider, corner): the plugin session
        // applies these to its living instances instead of choosing them at creation time.
        CONTROL_CENTER_PLUGIN to Group(systemUi) { it in SystemUiLiveSettings.CONTROL_CENTER_PLUGIN_KEYS },
        GLASS to Group(systemUi) { it in SystemUiLiveSettings.GLASS_KEYS },
        // The battery monitor lives in SystemUI; charging itself is handled by the AOD/battery rules.
        LOCKSCREEN to Group(systemUi) { it in SystemUiLiveSettings.LOCKSCREEN_KEYS },
        POWER_SAVE to Group(systemUi) { it == Preferences.KEY_BATTERY_AUTO_POWER_SAVE_ENABLED || it == Preferences.KEY_BATTERY_AUTO_POWER_SAVE_THRESHOLD || it == Preferences.KEY_EXIT_POWER_SAVE_WHEN_CHARGING },
        FORCE_SCREENSHOT to Group(setOf("android")) { it == Preferences.KEY_FORCE_SCREENSHOT },
        SCREENSHOT to Group(setOf("com.miui.screenshot")) {
            it == Preferences.KEY_SCREENSHOT_AUTO_FRAME || it == Preferences.KEY_SCREENSHOT_FRAME_EDITOR_MODE ||
                it == Preferences.KEY_SCREENSHOT_COPY_TO_CLIPBOARD || it == Preferences.KEY_SCREENSHOT_PICTURES_DIRECTORY
        },
        BACK to Group(systemUi + setOf("android", "com.miui.home")) { it in backKeys },
        NATIVE to Group(systemUi + "com.miui.home") { it in nativeKeys },
        DOCK to Group(setOf("android")) { it == DockConfig.KEY },
        VOLUME to Group(systemUi + "android") { it == Preferences.KEY_VOLUME_KEY_STEP_COUNT || it == Preferences.KEY_VOLUME_KEY_STEP_SCOPE },
        XMSF to Group(setOf("com.xiaomi.xmsf")) { it == Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH },
        // system_server: DisplayPolicy asks for the attach state on every recents transition.
        GESTURE_BAR to Group(setOf("android")) {
            it == Preferences.KEY_GESTURE_BAR_ATTACH_TRANSITION || it == Preferences.KEY_HIDE_GESTURE_BAR_RECENTS
        },
        // SystemUI: HideBottomBarHooker caches its switches in volatile fields, so a committed
        // change must reach it through the dispatcher. Without this route the hooks keep the value
        // captured at install time and the switch only applies after a SystemUI restart.
        GESTURE_BAR_UI to Group(systemUi) {
            it == Preferences.KEY_HIDE_GESTURE_BAR || it == Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT ||
                it == Preferences.KEY_HIDE_GESTURE_BAR_RECENTS || it == Preferences.KEY_GESTURE_BAR_ATTACH_TRANSITION ||
                it == Preferences.KEY_HIDE_GESTURE_BAR_LAUNCHER
        },
        XIAOAI_GESTURES to Group(setOf("com.miui.voiceassist")) { it == Preferences.KEY_XIAOAI_GESTURE_OPTIONS },
        SETTINGS_AI to Group(setOf("com.android.settings")) {
            it == Preferences.KEY_SETTINGS_ONDEVICE_MODEL_ENTRY || it == Preferences.KEY_SETTINGS_GEMINI_ENTRY
        },
        SHARE_PANEL to Group(setOf("com.android.intentresolver")) { it == Preferences.KEY_SHARE_PANEL_SHOW_ALL },
        // Presence-deciding switches. These keys make a hooker install or disappear, so the owning
        // process must be told about them even though no visible value changes inside a callback.
        GATED_FEATURES to Group(
            setOf(
                "com.miui.securitycenter", "com.android.camera", "com.xiaomi.aicr",
                "com.miui.personalassistant", "com.miui.mediaeditor",
            )
        ) {
            it == Preferences.KEY_SECURITY_CENTER_SHOW_BERSERK_MODE ||
                it == Preferences.KEY_SECURITY_CENTER_SHOW_DETAILED_POWER_DATA ||
                it == Preferences.KEY_SECURITY_CENTER_RESTORE_POWER_RANKING ||
                it == Preferences.KEY_SECURITY_CENTER_MORE_BATTERY_INFO ||
                it == Preferences.KEY_AICR_COPY_DIRECT_DEFAULT_BROWSER ||
                it == Preferences.KEY_PA_MODEL_SPOOF || it == Preferences.KEY_PA_MODEL_SPOOF_MODEL ||
                it == Preferences.KEY_PA_MODEL_SPOOF_DEVICE ||
                it in watermarkKeys ||
                it in powerSaveKeys
        },
        APP_DETAILS to Group(setOf("com.miui.securitycenter")) { it == Preferences.KEY_APP_DETAILS_ICON_LAUNCH },
        SECURITY_POPUPS to Group(setOf("com.miui.securitycenter")) {
            it == Preferences.KEY_SECURITY_CENTER_HIDE_DOLBY_POPUP || it == Preferences.KEY_SECURITY_CENTER_HIDE_GAME_POPUP
        },
        SECURITY_DOCK to Group(setOf("com.miui.securitycenter")) {
            it == Preferences.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PINNED ||
                it == Preferences.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PREDICTIONS || it == Preferences.KEY_SECURITY_CENTER_DOCK_PREDICT_LIMIT
        },
        SECURITY_CHECKS to Group(setOf("com.miui.securitycenter", "com.miui.guardprovider")) {
            it == Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK ||
                it == Preferences.KEY_SECURITY_CENTER_SKIP_LOCAL_SU_CHECK ||
                it == Preferences.KEY_SECURITY_CENTER_DISABLE_SAFEPAY_AUTO_SCAN ||
                it == Preferences.KEY_SECURITY_CENTER_DISABLE_ROOT_SCAN_ITEM ||
                it == Preferences.KEY_SECURITY_CENTER_DISABLE_USB_INSTALL_INTERCEPT ||
                it == Preferences.KEY_SECURITY_CENTER_BYPASS_USB_INSTALL_NETWORK_VERIFY
        },
        // The Network Assistant classes are packaged in Security Center; its activities run in
        // the main process and its service/provider consumers also use the .remote process.
        OFFLINE_CONFIG to Group(setOf("com.miui.securitycenter", "com.android.contacts")) {
            it == Preferences.KEY_NETWORK_ASSISTANT_IGNORE_OFFLINE ||
                it == Preferences.KEY_CONTACTS_BUSINESS_HALL_IGNORE_OFFLINE
        }
    )
    val targets: Set<String> get() = groups.values.flatMap { it.targets }.toSet()
    fun target(process: String, systemServer: Boolean = false): String = if (systemServer || process == "system_server") "android" else {
        val base = process.substringBefore(':')
        when {
            // Every Security Center subprocess hosts part of the same feature set; before this,
            // `.power` was not normalized and therefore never received a settings snapshot at all
            // (its PowerSaveOverrideHooker and PowerRankingHooker only ever saw boot values).
            base in setOf("com.miui.securitycenter", "com.miui.securitycenter.remote",
                "com.miui.securitycenter.cache", "com.miui.securitycenter.power") -> "com.miui.securitycenter"
            else -> base
        }
    }
    fun forTarget(target: String): Set<String> = groups.filterValues { target in it.targets }.keys
    fun forProcess(process: String, systemServer: Boolean = false): Set<String> = forTarget(target(process, systemServer))
    fun owners(key: String): Set<String> = groups.filterValues { it.owns(key) }.keys
    fun owns(key: String): Boolean = owners(key).isNotEmpty()
    fun owns(target: String, key: String): Boolean = groups.values.any { target in it.targets && it.owns(key) }
    fun affected(key: String?): Set<String> = if (key == null || key == "prefs_epoch") groups.keys
        else groups.filterValues { it.owns(key) }.keys
    fun changed(before: Map<String, Any>, after: Map<String, Any>): Set<String> =
        (before.keys + after.keys).filter {
            // Ordering/commit identities are carried by the snapshot, not UI invalidations.
            it != "prefs_epoch" && it != Preferences.KEY_NATIVE_RULE_REVISION && before[it] != after[it]
        }.flatMap(::affected).toSet()
    fun subset(target: String, values: Map<String, Any>): Map<String, Any> = values.filterKeys { owns(target, it) }
}
