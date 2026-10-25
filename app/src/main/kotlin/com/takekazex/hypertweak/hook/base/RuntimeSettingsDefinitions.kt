package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsKeys

import com.takekazex.hypertweak.settings.contract.AodSettingsKeys

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.settings.contract.AppSettingsKeys


import com.takekazex.hypertweak.hook.BackGestureSettingsSnapshot
import com.takekazex.hypertweak.settings.contract.SystemUiLiveSettings
import com.takekazex.hypertweak.settings.contract.IconSettingsPreferencePolicy
import com.takekazex.hypertweak.settings.contract.DockConfig


/** Key predicates and process ownership. IDs remain in RuntimeSettingsGroups. */
internal object RuntimeSettingsDefinitions {
    const val ICONS = "systemui.icons"
    const val ISLANDS = "systemui.islands"
    const val NATIVE_PHOTO_PICKER = "system.native_photo_picker"
    // Retain the aggregate group ID so pre-split saved snapshots remain readable.
    const val NATIVE_FILE_PICKER = "system.native_picker"
    const val DARK_MODE = "system.dark_mode"
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
        PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER, PlatformSettingsKeys.KEY_THREE_ISLANDS, PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS,
        PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS, SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND,
        PlatformSettingsKeys.KEY_ISLAND_NARROW_TIME_FONT, PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON,
        PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST,
        PlatformSettingsKeys.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST
    )
    val backKeys = BackGestureSettingsSnapshot.KEYS.toSet() + setOf(
        "back_gesture_predictive_packages", "contextual_search_long_press",
        "gesture_trigger_height_percent", "gesture_trigger_position_percent", "aosp_gesture_hyperos_haptics"
    )
    private val aodKeys = setOf(
        AodSettingsKeys.KEY_AOD_BATTERY_ICON_VISIBLE, AodSettingsKeys.KEY_AOD_DUO_ENABLED,
        AodSettingsKeys.KEY_AOD_BATTERY_PERCENT_VISIBLE, AodSettingsKeys.KEY_AOD_PERCENT_POSITION,
        AodSettingsKeys.KEY_AOD_DUO_CENTER_CONTENT
    )
    private val keyguardKeys = setOf(
        SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_DATE,
        SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT, SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD,
        SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN, SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH
    )
    /** Master and per-group power-save switches that decide whether the override hooker exists. */
    private val powerSaveKeys = setOf(
        PlatformSettingsKeys.KEY_POWER_SAVE_OVERRIDE_MASTER,
        PlatformSettingsKeys.KEY_KEEP_REFRESH_RATE_IN_POWER_SAVE,
        PlatformSettingsKeys.KEY_KEEP_HAPTIC_FEEDBACK_IN_POWER_SAVE,
        PlatformSettingsKeys.KEY_KEEP_SYSTEM_SOUNDS_IN_POWER_SAVE,
        PlatformSettingsKeys.KEY_KEEP_WAKEUP_GESTURES_IN_POWER_SAVE,
    )
    private val watermarkKeys = setOf(
        PlatformSettingsKeys.KEY_WM_UNLOCK_MASTER, PlatformSettingsKeys.KEY_WM_LEICA, PlatformSettingsKeys.KEY_WM_XIAOMI,
        PlatformSettingsKeys.KEY_WM_REDMI, PlatformSettingsKeys.KEY_WM_POCO, PlatformSettingsKeys.KEY_WM_DISNEY1,
        PlatformSettingsKeys.KEY_WM_DISNEY2, PlatformSettingsKeys.KEY_WM_DISNEY3, PlatformSettingsKeys.KEY_WM_VICTORIA,
        PlatformSettingsKeys.KEY_WM_LCC, PlatformSettingsKeys.KEY_WM_DOWNLOAD_ALL, PlatformSettingsKeys.KEY_WM_CAMERA,
    )
    private val nativeKeys = setOf(PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON, PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS,
        PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS, PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT,
        PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC, PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW, PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK, PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS, "prefs_epoch")
    val strictBooleanKeys = setOf(
        PlatformSettingsKeys.KEY_NATIVE_PICKER, PlatformSettingsKeys.KEY_NATIVE_PHOTO_PICKER, PlatformSettingsKeys.KEY_NATIVE_FILE_PICKER,
        PlatformSettingsKeys.KEY_SETTINGS_ONDEVICE_MODEL_ENTRY,
        PlatformSettingsKeys.KEY_SETTINGS_GEMINI_ENTRY,
        PlatformSettingsKeys.KEY_FORCE_SCREENSHOT, PlatformSettingsKeys.KEY_DARK_MODE_IMMEDIATE_SCHEDULE,
        PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_DOLBY_POPUP,
        PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_GAME_POPUP,
        PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PINNED,
        PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PREDICTIONS,
        PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS,
        SystemUiSettingsKeys.KEY_NOTIFICATION_WHITELIST_BYPASS,
        PlatformSettingsKeys.KEY_SHARE_PANEL_SHOW_ALL,
        AppSettingsKeys.KEY_APP_DETAILS_ICON_LAUNCH,
        PlatformSettingsKeys.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK,
        PlatformSettingsKeys.KEY_SECURITY_CENTER_SKIP_LOCAL_SU_CHECK,
        PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_SAFEPAY_AUTO_SCAN,
        PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_ROOT_SCAN_ITEM,
        PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_USB_INSTALL_INTERCEPT,
        PlatformSettingsKeys.KEY_SECURITY_CENTER_BYPASS_USB_INSTALL_NETWORK_VERIFY,
        PlatformSettingsKeys.KEY_NETWORK_ASSISTANT_IGNORE_OFFLINE,
        PlatformSettingsKeys.KEY_CONTACTS_BUSINESS_HALL_IGNORE_OFFLINE,
    )
    data class Group(val targets: Set<String>, val owns: (String) -> Boolean)
    private val systemUi = setOf("com.android.systemui")
    val groups = linkedMapOf(
        ICONS to Group(systemUi) { it != "prefs_epoch" && it !in islandKeys && IconSettingsPreferencePolicy.affectsIcons(it) },
        ISLANDS to Group(systemUi) { it in islandKeys },
        // Both AOD hosts: SystemUI's keyguard battery row and the AOD plugin (hosted by SystemUI
        // and by the standalone com.miui.aod process).
        AOD to Group(systemUi + "com.miui.aod") { it in aodKeys },
        AOD_CAPABILITY to Group(systemUi + setOf("com.miui.aod", "com.android.settings")) {
            it == AodSettingsKeys.KEY_AOD_FULLSCREEN
        },
        NOTIFICATION_ICONS to Group(systemUi) { it == SystemUiSettingsKeys.KEY_NOTIFICATION_ICON_FILTER_MODE || it == SystemUiSettingsKeys.KEY_NOTIFICATION_MORE_SETTINGS },
        NOTIFICATION_MINI_WINDOW to Group(systemUi) { it == SystemUiSettingsKeys.KEY_NOTIFICATION_MINI_WINDOW },
        NOTIFICATION_WHITELIST to Group(systemUi) { it == SystemUiSettingsKeys.KEY_NOTIFICATION_WHITELIST_BYPASS },
        FREEFORM_BLUR to Group(systemUi) { it == PlatformSettingsKeys.KEY_FREEFORM_BLUR_TRANSITION },
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
        POWER_SAVE to Group(systemUi) { it == PlatformSettingsKeys.KEY_BATTERY_AUTO_POWER_SAVE_ENABLED || it == PlatformSettingsKeys.KEY_BATTERY_AUTO_POWER_SAVE_THRESHOLD || it == PlatformSettingsKeys.KEY_EXIT_POWER_SAVE_WHEN_CHARGING },
        NATIVE_PHOTO_PICKER to Group(setOf("android")) {
            it == PlatformSettingsKeys.KEY_NATIVE_PHOTO_PICKER || it == PlatformSettingsKeys.KEY_NATIVE_PICKER
        },
        NATIVE_FILE_PICKER to Group(setOf("android", "com.android.photopicker")) {
            it == PlatformSettingsKeys.KEY_NATIVE_FILE_PICKER || it == PlatformSettingsKeys.KEY_NATIVE_PICKER
        },
        DARK_MODE to Group(setOf("android")) { it == PlatformSettingsKeys.KEY_DARK_MODE_IMMEDIATE_SCHEDULE },
        FORCE_SCREENSHOT to Group(setOf("android")) { it == PlatformSettingsKeys.KEY_FORCE_SCREENSHOT },
        SCREENSHOT to Group(setOf("com.miui.screenshot")) {
            it == ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME || it == ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE ||
                it == ScreenshotSettingsKeys.KEY_SCREENSHOT_COPY_TO_CLIPBOARD || it == ScreenshotSettingsKeys.KEY_SCREENSHOT_PICTURES_DIRECTORY
        },
        BACK to Group(systemUi + setOf("android", "com.miui.home")) { it in backKeys },
        NATIVE to Group(systemUi + "com.miui.home") { it in nativeKeys },
        DOCK to Group(setOf("android")) { it == DockConfig.KEY },
        VOLUME to Group(systemUi + "android") { it == PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_COUNT || it == PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_SCOPE },
        XMSF to Group(setOf("com.xiaomi.xmsf")) { it == PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH },
        // system_server: DisplayPolicy asks for the attach state on every recents transition.
        GESTURE_BAR to Group(setOf("android")) {
            it == SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION || it == PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_RECENTS
        },
        // SystemUI: HideBottomBarHooker caches its switches in volatile fields, so a committed
        // change must reach it through the dispatcher. Without this route the hooks keep the value
        // captured at install time and the switch only applies after a SystemUI restart.
        GESTURE_BAR_UI to Group(systemUi) {
            it == PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR || it == SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT ||
                it == PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_RECENTS || it == SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION ||
                it == PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_LAUNCHER
        },
        XIAOAI_GESTURES to Group(setOf("com.miui.voiceassist")) { it == PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS },
        SETTINGS_AI to Group(setOf("com.android.settings")) {
            it == PlatformSettingsKeys.KEY_SETTINGS_ONDEVICE_MODEL_ENTRY || it == PlatformSettingsKeys.KEY_SETTINGS_GEMINI_ENTRY
        },
        SHARE_PANEL to Group(setOf("com.android.intentresolver")) { it == PlatformSettingsKeys.KEY_SHARE_PANEL_SHOW_ALL },
        // Presence-deciding switches. These keys make a hooker install or disappear, so the owning
        // process must be told about them even though no visible value changes inside a callback.
        GATED_FEATURES to Group(
            setOf(
                "com.miui.securitycenter", "com.android.camera", "com.xiaomi.aicr",
                "com.miui.personalassistant", "com.miui.mediaeditor",
            )
        ) {
            it == PlatformSettingsKeys.KEY_SECURITY_CENTER_SHOW_BERSERK_MODE ||
                it == PlatformSettingsKeys.KEY_SECURITY_CENTER_SHOW_DETAILED_POWER_DATA ||
                it == PlatformSettingsKeys.KEY_SECURITY_CENTER_RESTORE_POWER_RANKING ||
                it == PlatformSettingsKeys.KEY_SECURITY_CENTER_MORE_BATTERY_INFO ||
                it == PlatformSettingsKeys.KEY_AICR_COPY_DIRECT_DEFAULT_BROWSER ||
                it == PlatformSettingsKeys.KEY_PA_MODEL_SPOOF || it == PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_MODEL ||
                it == PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_DEVICE ||
                it in watermarkKeys ||
                it in powerSaveKeys
        },
        APP_DETAILS to Group(setOf("com.miui.securitycenter")) { it == AppSettingsKeys.KEY_APP_DETAILS_ICON_LAUNCH },
        SECURITY_POPUPS to Group(setOf("com.miui.securitycenter")) {
            it == PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_DOLBY_POPUP || it == PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_GAME_POPUP
        },
        SECURITY_DOCK to Group(setOf("com.miui.securitycenter")) {
            it == PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PINNED ||
                it == PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PREDICTIONS || it == PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_PREDICT_LIMIT
        },
        SECURITY_CHECKS to Group(setOf("com.miui.securitycenter", "com.miui.guardprovider")) {
            it == PlatformSettingsKeys.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK ||
                it == PlatformSettingsKeys.KEY_SECURITY_CENTER_SKIP_LOCAL_SU_CHECK ||
                it == PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_SAFEPAY_AUTO_SCAN ||
                it == PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_ROOT_SCAN_ITEM ||
                it == PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_USB_INSTALL_INTERCEPT ||
                it == PlatformSettingsKeys.KEY_SECURITY_CENTER_BYPASS_USB_INSTALL_NETWORK_VERIFY
        },
        // The Network Assistant classes are packaged in Security Center; its activities run in
        // the main process and its service/provider consumers also use the .remote process.
        OFFLINE_CONFIG to Group(setOf("com.miui.securitycenter", "com.android.contacts")) {
            it == PlatformSettingsKeys.KEY_NETWORK_ASSISTANT_IGNORE_OFFLINE ||
                it == PlatformSettingsKeys.KEY_CONTACTS_BUSINESS_HALL_IGNORE_OFFLINE
        }
    )
}
