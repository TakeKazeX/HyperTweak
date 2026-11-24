package com.takekazex.hypertweak.hook.runtime.settings

import com.takekazex.hypertweak.settings.contract.FreeformSettingsKeys
import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys

import com.takekazex.hypertweak.settings.contract.ScreenRecorderSettingsKeys
import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsKeys

import com.takekazex.hypertweak.settings.contract.AodSettingsKeys
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.settings.contract.PackageManagerSettingsKeys
import com.takekazex.hypertweak.settings.contract.PhraseSettingsKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.settings.contract.AppSettingsKeys


import com.takekazex.hypertweak.settings.contract.SystemUiLiveSettings
import com.takekazex.hypertweak.settings.contract.IconSettingsPreferencePolicy


/** Key sets and process ownership per group. Group IDs are declared once, in [RuntimeSettingsGroups]. */
internal object RuntimeSettingsDefinitions {
    private val phraseTargets = java.util.concurrent.CopyOnWriteArraySet<String>(setOf(PhraseSettingsKeys.PACKAGE))
    private val imeTargets = java.util.concurrent.CopyOnWriteArraySet<String>(setOf("android"))
    fun registerImeTargets(packages: Set<String>) {
        imeTargets.addAll(packages)
        phraseTargets.addAll(packages)
    }
    private val islandKeys = setOf(
        PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER, PlatformSettingsKeys.KEY_THREE_ISLANDS, PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS,
        PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS, SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND,
        PlatformSettingsKeys.KEY_ISLAND_NARROW_TIME_FONT, PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON,
        PlatformSettingsKeys.KEY_ISLAND_PULL_DOWN_STRETCH, PlatformSettingsKeys.KEY_ISLAND_LOTTIE_RESUME,
        PlatformSettingsKeys.KEY_ISLAND_FLASHLIGHT_CONTROL,
        PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_MULTI_SESSION,
        PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST,
        PlatformSettingsKeys.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST
    )
    val backKeys = BackGestureSettingsSnapshot.KEYS.toSet() + setOf(
        "back_gesture_predictive_packages", "contextual_search_long_press",
        "aosp_gesture_hyperos_haptics"
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
    private val controlCenterPrivacyKeys = setOf(
        SystemUiSettingsKeys.KEY_CC_PRIVACY_ENABLED, SystemUiSettingsKeys.KEY_CC_PRIVACY_CENTER,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_BACKGROUND, SystemUiSettingsKeys.KEY_CC_PRIVACY_COLORED,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_NAMES, SystemUiSettingsKeys.KEY_CC_PRIVACY_ALL_APPS,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_ARROW, SystemUiSettingsKeys.KEY_CC_PRIVACY_DOT,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_ICON_SIZE, SystemUiSettingsKeys.KEY_CC_PRIVACY_TEXT_SIZE,
        SystemUiSettingsKeys.KEY_CC_PRIVACY_OFFSET,
    )
    private val watermarkKeys = setOf(
        PlatformSettingsKeys.KEY_WM_UNLOCK_MASTER, PlatformSettingsKeys.KEY_WM_LEICA, PlatformSettingsKeys.KEY_WM_XIAOMI,
        PlatformSettingsKeys.KEY_WM_REDMI, PlatformSettingsKeys.KEY_WM_POCO, PlatformSettingsKeys.KEY_WM_DISNEY1,
        PlatformSettingsKeys.KEY_WM_DISNEY2, PlatformSettingsKeys.KEY_WM_DISNEY3, PlatformSettingsKeys.KEY_WM_VICTORIA,
        PlatformSettingsKeys.KEY_WM_LCC, PlatformSettingsKeys.KEY_WM_DOWNLOAD_ALL, PlatformSettingsKeys.KEY_WM_CAMERA,
    )
    private val nativeKeys = setOf(LauncherDesktopKeys.KEY_ICON_THEME_MODE, LauncherDesktopKeys.KEY_ICON_THEME_COLOR, LauncherDesktopKeys.KEY_GRID, LauncherDesktopKeys.KEY_ICON_SIZE_PERCENT, PlatformSettingsKeys.KEY_HIDE_RECENTS_CLEAR_BUTTON, PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS,
        PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS, PlatformSettingsKeys.KEY_ALLOW_ANDROID_WIDGETS_TO_ASSISTANT,
        PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION, PlatformSettingsKeys.KEY_RECENTS_CARD_HAPTIC, PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW, PlatformSettingsKeys.KEY_ANDROID_WIDGET_STACK, PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS, PlatformSettingsKeys.KEY_NATIVE_PAD_DOCK,
        PlatformSettingsKeys.KEY_NATIVE_DOCK_GLASS, "prefs_epoch")
    private val aospUiKeys = setOf(
        PlatformSettingsKeys.KEY_AOSP_POWER_MENU, PlatformSettingsKeys.KEY_AOSP_VOLUME_PANEL,
        PlatformSettingsKeys.KEY_AOSP_VOLUME_HAPTIC_MIUI, PlatformSettingsKeys.KEY_EXTEND_UNLOCK_FIX,
        PlatformSettingsKeys.KEY_AOSP_CLIPBOARD_EDITOR,
    )
    private val aospSecurityKeys = setOf(
        PlatformSettingsKeys.KEY_AOSP_APP_INFO_ENTRY, PlatformSettingsKeys.KEY_AOSP_APP_MANAGER_ENTRY,
    )
    private val googleKeys = setOf(
        PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN, PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE,
        PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS, PlatformSettingsKeys.KEY_REMOVE_GMS_RESTRICTION,
        PlatformSettingsKeys.KEY_UNLOCK_PASSKEY,
        PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED,
    )
    private val policyKeys = setOf(
        PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR,
        PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST,
        PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES,
        PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK,
        PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE,
        PlatformSettingsKeys.KEY_UNLOCK_THIRD_PARTY_DARK_MODE,
        PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME,
        PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING,
        SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS,
        SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS,
        SystemUiSettingsKeys.KEY_NOTIFICATION_BADGE,
        SystemUiSettingsKeys.KEY_NOTIFICATION_MORE_SETTINGS,
        SystemUiSettingsKeys.KEY_NOTIFICATION_BLOCK_FOLD,
    )
    private val passivePolicyKeys = setOf(
        PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK,
        PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL,
        PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON,
        PlatformSettingsKeys.KEY_AIVS_REDIRECT,
        PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO,
        PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC,
        PlatformSettingsKeys.KEY_POWERKEEPER_BLOCK_WHITELIST_RESTORE,
    )
    private val experimentalKeys = setOf(
        PlatformSettingsKeys.KEY_UNLOCK_MORE_VISUAL_PERCEPTION,
        PlatformSettingsKeys.KEY_UNLOCK_MORE_AON_GESTURES,
        PlatformSettingsKeys.KEY_UNLOCK_ADAPTIVE_REFRESH_PRO
    )
    /**
     * Process ownership of live settings. [strict] keys are owned too and reject a snapshot whose
     * value is not a Boolean; strictness belongs to the key, so every group owning it lists it there.
     * [matches] is only for ownership that is a policy rather than a key list.
     */
    class Group(
        val targets: Set<String>,
        keys: Set<String> = emptySet(),
        val strict: Set<String> = emptySet(),
        val matches: ((String) -> Boolean)? = null,
    ) {
        val keys: Set<String> = keys + strict
        fun owns(key: String): Boolean = key in keys || matches?.invoke(key) == true
    }
    private val systemUi = setOf("com.android.systemui")
    val groups: Map<String, Group> = linkedMapOf(
        RuntimeSettingsGroups.POWER_BUTTON to Group(setOf("android", "com.google.android.googlequicksearchbox"), keys = setOf(PlatformSettingsKeys.KEY_POWER_BUTTON_ACTION), strict = setOf(PlatformSettingsKeys.KEY_POWER_BUTTON_CTS)),
        RuntimeSettingsGroups.EXPERIMENTAL_CAPABILITIES to Group(setOf("android", "com.android.settings"), strict = experimentalKeys),
        RuntimeSettingsGroups.CAMERA to Group(setOf("com.android.camera"), keys = com.takekazex.hypertweak.settings.contract.CameraSettingsKeys.keys, strict = com.takekazex.hypertweak.settings.contract.CameraSettingsKeys.booleanKeys),
        RuntimeSettingsGroups.MONET to Group(systemUi, strict = setOf(SystemUiSettingsKeys.KEY_IMMEDIATE_MONET_REFRESH)),
        RuntimeSettingsGroups.PHONE_NETWORK_TYPE to Group(setOf("com.android.phone"), strict = setOf(PlatformSettingsKeys.KEY_ENABLE_NETWORK_TYPE_MENU)),
        RuntimeSettingsGroups.SETTINGS_DISPLAY_NETWORK to Group(setOf("com.android.settings"), strict = setOf(
            PlatformSettingsKeys.KEY_LINK_TURBO_ALL_APPS,
            PlatformSettingsKeys.KEY_ALLOW_NEVER_SCREEN_TIMEOUT,
        )),
        RuntimeSettingsGroups.MISETTINGS_REFRESH_RATE to Group(setOf(com.takekazex.hypertweak.settings.contract.MiSettingsSettingsKeys.PACKAGE), strict = setOf(com.takekazex.hypertweak.settings.contract.MiSettingsSettingsKeys.KEY_MORE_REFRESH_RATES)),
        RuntimeSettingsGroups.APP_REFRESH_RATES to Group(setOf(
            com.takekazex.hypertweak.settings.contract.MiSettingsSettingsKeys.PACKAGE,
            com.takekazex.hypertweak.settings.contract.MiSettingsSettingsKeys.POWERKEEPER_PACKAGE,
        ), strict = setOf(com.takekazex.hypertweak.settings.contract.MiSettingsSettingsKeys.KEY_PER_APP_REFRESH_RATES)),
        RuntimeSettingsGroups.MODULE_ENTRY to Group(setOf("com.android.settings"), strict = setOf(PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS)),
        RuntimeSettingsGroups.IME to Group(imeTargets, keys = setOf( PlatformSettingsKeys.KEY_AOSP_IME_ENABLED, PlatformSettingsKeys.KEY_AOSP_IME_PACKAGES, PlatformSettingsKeys.KEY_AOSP_IME_MIUI_IME_LIST, PlatformSettingsKeys.KEY_AOSP_IME_RAISE_STYLE, PlatformSettingsKeys.KEY_AOSP_IME_NAV_BAR_START, PlatformSettingsKeys.KEY_AOSP_IME_NAV_BAR_END, ), strict = setOf(PlatformSettingsKeys.KEY_AOSP_IME_ENABLED, PlatformSettingsKeys.KEY_AOSP_IME_MIUI_IME_LIST)),
        RuntimeSettingsGroups.POLICY_FEATURES to Group(setOf(
            "android", "com.android.systemui", "com.android.settings",
            "com.android.providers.downloads", "com.miui.guardprovider", "com.milink.service",
            "com.android.phone", "com.android.thememanager", "com.xiaomi.trustservice",
        ), strict = policyKeys),
        RuntimeSettingsGroups.PASSIVE_POLICIES to Group(setOf(
            "com.android.providers.downloads.ui", "com.android.settings",
            "com.milink.service", "com.xiaomi.bluetooth", "com.miui.voiceassist", "com.xiaomi.finddevice",
            "com.miui.powerkeeper",
        ), strict = passivePolicyKeys),
        RuntimeSettingsGroups.DOWNLOAD_FOCUS to Group(setOf("com.android.providers.downloads", "com.android.systemui"), strict = setOf(PlatformSettingsKeys.KEY_DOWNLOAD_FOCUS_NOTIFICATION)),
        RuntimeSettingsGroups.FLASHLIGHT_PAGE to Group(setOf("miui.systemui.plugin"), keys = setOf(PlatformSettingsKeys.KEY_ISLAND_FLASHLIGHT_CONTROL)),
        RuntimeSettingsGroups.GOOGLE_APP to Group(setOf("com.google.android.googlequicksearchbox"), strict = setOf(PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN, PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE)),
        RuntimeSettingsGroups.GOOGLE_SETTINGS to Group(setOf("com.android.settings"), strict = setOf(PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS)),
        RuntimeSettingsGroups.GMS_RESTRICTIONS to Group(setOf("android"), strict = setOf(PlatformSettingsKeys.KEY_REMOVE_GMS_RESTRICTION)),
        RuntimeSettingsGroups.PASSKEY to Group(setOf("android", "com.android.settings", "com.miui.securitycenter", "com.xiaomi.scanner"), strict = setOf(PlatformSettingsKeys.KEY_UNLOCK_PASSKEY)),
        RuntimeSettingsGroups.FCM to Group(setOf("android", "com.miui.powerkeeper"), strict = setOf(PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED)),
        RuntimeSettingsGroups.ICONS to Group(systemUi, matches = { it != "prefs_epoch" && it !in islandKeys && IconSettingsPreferencePolicy.affectsIcons(it) }),
        // The Duo status icon changes the island's right status budget as well as the icons.
        RuntimeSettingsGroups.ISLANDS to Group(systemUi, keys = setOf(IconSettingsKeys.KEY_ICON_SIGNAL_STYLE, IconSettingsKeys.KEY_ICON_DUO_ENABLED) + islandKeys),
        // Both AOD hosts: SystemUI's keyguard battery row and the AOD plugin (hosted by SystemUI
        // and by the standalone com.miui.aod process).
        RuntimeSettingsGroups.AOD to Group(systemUi + "com.miui.aod", keys = aodKeys),
        RuntimeSettingsGroups.AOD_CAPABILITY to Group(systemUi + setOf("com.miui.aod", "com.android.settings"), keys = setOf(AodSettingsKeys.KEY_AOD_FULLSCREEN)),
        RuntimeSettingsGroups.NOTIFICATION_ICONS to Group(systemUi, keys = setOf(SystemUiSettingsKeys.KEY_NOTIFICATION_ICON_FILTER_MODE), strict = setOf(SystemUiSettingsKeys.KEY_NOTIFICATION_MORE_SETTINGS)),
        RuntimeSettingsGroups.NOTIFICATION_MINI_WINDOW to Group(systemUi, keys = setOf(SystemUiSettingsKeys.KEY_NOTIFICATION_MINI_WINDOW)),
        RuntimeSettingsGroups.NOTIFICATION_WHITELIST to Group(systemUi, strict = setOf(SystemUiSettingsKeys.KEY_NOTIFICATION_WHITELIST_BYPASS)),
        RuntimeSettingsGroups.FREEFORM_BLUR to Group(systemUi, keys = setOf(PlatformSettingsKeys.KEY_FREEFORM_BLUR_TRANSITION)),
        RuntimeSettingsGroups.MOTION_CUES_FREEFORM to Group(systemUi, keys = setOf(PlatformSettingsKeys.KEY_MOTION_CUES_FREEFORM_FIX)),
        RuntimeSettingsGroups.KEYGUARD to Group(systemUi, keys = keyguardKeys),
        RuntimeSettingsGroups.HEADER to Group(systemUi, keys = SystemUiLiveSettings.HEADER_KEYS),
        RuntimeSettingsGroups.NOTIFICATION_TEXT to Group(systemUi, keys = SystemUiLiveSettings.NOTIFICATION_TEXT_KEYS),
        // Both media-card renderers: the host hooks in SystemUI and the control-center plugin's
        // device-icon child hook, which is chosen when a plugin session is created.
        RuntimeSettingsGroups.MEDIA_CARD to Group(systemUi, keys = SystemUiLiveSettings.MEDIA_CARD_KEYS),
        // Control-center plugin sub-hooks (editor, resize, slider, corner): the plugin session
        // applies these to its living instances instead of choosing them at creation time.
        RuntimeSettingsGroups.CONTROL_CENTER_PLUGIN to Group(systemUi, keys = SystemUiLiveSettings.CONTROL_CENTER_PLUGIN_KEYS),
        RuntimeSettingsGroups.CONTROL_CENTER_PRIVACY to Group(systemUi, keys = controlCenterPrivacyKeys),
        RuntimeSettingsGroups.GLASS to Group(systemUi, keys = SystemUiLiveSettings.GLASS_KEYS),
        // The battery monitor lives in SystemUI; charging itself is handled by the AOD/battery rules.
        RuntimeSettingsGroups.LOCKSCREEN to Group(systemUi, keys = SystemUiLiveSettings.LOCKSCREEN_KEYS),
        RuntimeSettingsGroups.POWER_SAVE to Group(systemUi, keys = setOf(PlatformSettingsKeys.KEY_BATTERY_AUTO_POWER_SAVE_ENABLED, PlatformSettingsKeys.KEY_BATTERY_AUTO_POWER_SAVE_THRESHOLD, PlatformSettingsKeys.KEY_EXIT_POWER_SAVE_WHEN_CHARGING)),
        RuntimeSettingsGroups.NATIVE_PHOTO_PICKER to Group(setOf("android"), strict = setOf(PlatformSettingsKeys.KEY_NATIVE_PHOTO_PICKER, PlatformSettingsKeys.KEY_NATIVE_PICKER)),
        RuntimeSettingsGroups.NATIVE_FILE_PICKER to Group(setOf("android", "com.android.photopicker"), strict = setOf(PlatformSettingsKeys.KEY_NATIVE_FILE_PICKER, PlatformSettingsKeys.KEY_NATIVE_PICKER)),
        RuntimeSettingsGroups.PACKAGE_MANAGER to Group(setOf("android"), strict = PackageManagerSettingsKeys.keys),
        RuntimeSettingsGroups.AOSP_INSTALLER to Group(setOf("android"), strict = setOf(PlatformSettingsKeys.KEY_AOSP_PACKAGE_INSTALLER)),
        RuntimeSettingsGroups.AOSP_SYSTEM_UI to Group(systemUi, strict = aospUiKeys),
        RuntimeSettingsGroups.AOSP_SECURITY to Group(setOf("com.miui.securitycenter"), strict = aospSecurityKeys),
        RuntimeSettingsGroups.DARK_MODE to Group(setOf("android"), strict = setOf(PlatformSettingsKeys.KEY_DARK_MODE_IMMEDIATE_SCHEDULE)),
        RuntimeSettingsGroups.FORCE_SCREENSHOT to Group(setOf("android"), strict = setOf(PlatformSettingsKeys.KEY_FORCE_SCREENSHOT)),
        RuntimeSettingsGroups.HTML_VIEWER_MILLET to Group(setOf("android"), keys = setOf(PlatformSettingsKeys.KEY_HTML_VIEWER_MILLET)),
        RuntimeSettingsGroups.SCREEN_RECORDER to Group(
            setOf(ScreenRecorderSettingsKeys.PACKAGE),
            strict = setOf(ScreenRecorderSettingsKeys.KEY_SAVE_TO_MOVIES, ScreenRecorderSettingsKeys.KEY_MORE_FRAME_RATES),
        ),
        RuntimeSettingsGroups.SCREENSHOT to Group(setOf("com.miui.screenshot"), keys = setOf(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME, ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE, ScreenshotSettingsKeys.KEY_SCREENSHOT_COPY_TO_CLIPBOARD, ScreenshotSettingsKeys.KEY_SCREENSHOT_PICTURES_DIRECTORY)),
        RuntimeSettingsGroups.BACK to Group(systemUi + setOf("android", "com.miui.home"), keys = backKeys),
        RuntimeSettingsGroups.NATIVE to Group(systemUi + "com.miui.home", keys = nativeKeys, strict = setOf(PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS, PlatformSettingsKeys.KEY_NATIVE_PAD_DOCK)),
        // The app-volume switch reaches both the SystemUI dialog and system_server's MiSound entry gate.
        RuntimeSettingsGroups.VOLUME to Group(systemUi + "android", keys = setOf(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_ENABLED, PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_COUNT, PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_SCOPE, PlatformSettingsKeys.KEY_VOLUME_FIRST_PRESS, SystemUiSettingsKeys.KEY_VOLUME_APP_VOLUME)),
        RuntimeSettingsGroups.XMSF to Group(setOf("com.xiaomi.xmsf"), keys = setOf(PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH)),
        RuntimeSettingsGroups.FREEFORM to Group(systemUi + "android", strict = FreeformSettingsKeys.keys),
        RuntimeSettingsGroups.AICR_FREEFORM to Group(setOf("com.xiaomi.aicr"), strict = setOf(FreeformSettingsKeys.KEY_JUMP) + FreeformSettingsKeys.copyDirectKeys),
        // system_server: DisplayPolicy asks for the attach state on every recents transition.
        RuntimeSettingsGroups.GESTURE_BAR to Group(setOf("android"), keys = setOf(SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION, PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_RECENTS)),
        // SystemUI: HideBottomBarHooker caches its switches in volatile fields, so a committed
        // change must reach it through the dispatcher. Without this route the hooks keep the value
        // captured at install time and the switch only applies after a SystemUI restart.
        RuntimeSettingsGroups.GESTURE_BAR_UI to Group(systemUi, keys = setOf(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR, SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT, PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_RECENTS, SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION, PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_LAUNCHER)),
        RuntimeSettingsGroups.XIAOAI_GESTURES to Group(setOf("com.miui.voiceassist"), strict = setOf(PlatformSettingsKeys.KEY_XIAOAI_GESTURE_OPTIONS)),
        RuntimeSettingsGroups.SETTINGS_AI to Group(setOf("com.android.settings"), strict = setOf(PlatformSettingsKeys.KEY_SETTINGS_ONDEVICE_MODEL_ENTRY, PlatformSettingsKeys.KEY_SETTINGS_GEMINI_ENTRY)),
        RuntimeSettingsGroups.ANALYTICS to Group(
            setOf(com.takekazex.hypertweak.settings.contract.AnalyticsSettingsKeys.PACKAGE),
            strict = setOf(com.takekazex.hypertweak.settings.contract.AnalyticsSettingsKeys.KEY_ANALYTICS_BLOCK_TELEMETRY),
        ),
        RuntimeSettingsGroups.PHRASE to Group(
            phraseTargets,
            keys = setOf(PhraseSettingsKeys.KEY_CLIPBOARD_COUNT, PhraseSettingsKeys.KEY_CLIPBOARD_DAYS),
            strict = setOf(
                PhraseSettingsKeys.KEY_ALLOW_IME_HISTORY,
                PhraseSettingsKeys.KEY_UNLOCK_LIMITS,
                PhraseSettingsKeys.KEY_CLIPBOARD_LIMITS,
            ),
        ),
        RuntimeSettingsGroups.SMS to Group(setOf(com.takekazex.hypertweak.settings.contract.SmsSettingsKeys.PACKAGE), strict = setOf(com.takekazex.hypertweak.settings.contract.SmsSettingsKeys.KEY_SMS_AUTO_COPY_CODE)),
        RuntimeSettingsGroups.MARKET to Group(setOf(com.takekazex.hypertweak.settings.contract.MarketSettingsKeys.PACKAGE), strict = com.takekazex.hypertweak.settings.contract.MarketSettingsKeys.keys),
        RuntimeSettingsGroups.COMMUNITY to Group(setOf(com.takekazex.hypertweak.settings.contract.CommunitySettings.PACKAGE), keys = com.takekazex.hypertweak.settings.contract.CommunitySettings.keys, strict = setOf(com.takekazex.hypertweak.settings.contract.CommunitySettingsKeys.KEY_COMMUNITY_BLOCK_DETECTION, com.takekazex.hypertweak.settings.contract.CommunitySettingsKeys.KEY_COMMUNITY_DEVICE_MODIFY)),
        RuntimeSettingsGroups.SHARE_PANEL to Group(setOf("com.android.intentresolver"), strict = setOf(PlatformSettingsKeys.KEY_SHARE_PANEL_SHOW_ALL)),
        // Presence-deciding switches. These keys make a hooker install or disappear, so the owning
        // process must be told about them even though no visible value changes inside a callback.
        RuntimeSettingsGroups.GATED_FEATURES to Group(
            setOf(
                "com.miui.securitycenter", "com.android.camera", "com.xiaomi.aicr",
                "com.miui.personalassistant", "com.miui.mediaeditor",
            ), keys = setOf(PlatformSettingsKeys.KEY_AICR_COPY_DIRECT_DEFAULT_BROWSER, PlatformSettingsKeys.KEY_PA_MODEL_SPOOF, PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_MODEL, PlatformSettingsKeys.KEY_PA_MODEL_SPOOF_DEVICE) + watermarkKeys, strict = powerSaveKeys + setOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_SHOW_BERSERK_MODE, PlatformSettingsKeys.KEY_SECURITY_CENTER_SHOW_DETAILED_POWER_DATA, PlatformSettingsKeys.KEY_SECURITY_CENTER_RESTORE_POWER_RANKING, PlatformSettingsKeys.KEY_SECURITY_CENTER_MORE_BATTERY_INFO)),
        RuntimeSettingsGroups.APP_DETAILS to Group(setOf("com.miui.securitycenter"), strict = setOf(
            AppSettingsKeys.KEY_APP_DETAILS_ICON_LAUNCH, AppSettingsKeys.KEY_APP_DETAILS_HIDE_REPORT,
            AppSettingsKeys.KEY_APP_DETAILS_ALLOW_DISABLE, AppSettingsKeys.KEY_APP_DETAILS_MORE_INFO,
            AppSettingsKeys.KEY_APP_DETAILS_NAMED_SHARE, AppSettingsKeys.KEY_APP_LINK_MANAGEMENT,
        )),
        RuntimeSettingsGroups.SECURITY_GAMEBOOSTER to Group(setOf("com.miui.securitycenter"), strict = setOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_FORCE_UNLOCK_GUNSIGHT, PlatformSettingsKeys.KEY_SECURITY_CENTER_FORCE_UNLOCK_MACRO)),
        RuntimeSettingsGroups.SECURITY_POPUPS to Group(setOf("com.miui.securitycenter"), strict = setOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_DOLBY_POPUP, PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_GAME_POPUP)),
        RuntimeSettingsGroups.SECURITY_SIDEBAR to Group(setOf("com.miui.securitycenter"), keys = setOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_SIDEBAR_MODE)),
        RuntimeSettingsGroups.SECURITY_DOCK to Group(setOf("com.miui.securitycenter"), keys = setOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_PREDICT_LIMIT), strict = setOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PINNED, PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PREDICTIONS)),
        RuntimeSettingsGroups.SECURITY_DOCK_SHORTCUTS to Group(setOf("com.miui.securitycenter"), strict = setOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_APP_SHORTCUTS, PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_APP_SHORTCUTS_FREEFORM)),
        RuntimeSettingsGroups.SECURITY_CHECKS to Group(setOf("com.miui.securitycenter", "com.miui.guardprovider"), strict = setOf(PlatformSettingsKeys.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK, PlatformSettingsKeys.KEY_SECURITY_CENTER_SKIP_LOCAL_SU_CHECK, PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_SAFEPAY_AUTO_SCAN, PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_ROOT_SCAN_ITEM, PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_USB_INSTALL_INTERCEPT, PlatformSettingsKeys.KEY_SECURITY_CENTER_BYPASS_USB_INSTALL_NETWORK_VERIFY)),
        RuntimeSettingsGroups.SECURITY_PRIVACY to Group(setOf("com.miui.securitycenter"), keys = setOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_ANTI_PEEPING_MODE), strict = setOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_PRIVACY_CALL_ENTRY, PlatformSettingsKeys.KEY_SECURITY_CENTER_FRONT_CAMERA_ASSISTANT_ENTRY, PlatformSettingsKeys.KEY_SECURITY_CENTER_CALL_PRIVACY_CAMERA_ENTRY)),
        RuntimeSettingsGroups.SECURITY_WARNINGS to Group(setOf("com.miui.securitycenter"), keys = setOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_LOW_BATTERY_MODE, PlatformSettingsKeys.KEY_SECURITY_CENTER_PERMISSION_WARNING_MODE), strict = setOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_LOW_BATTERY_WARNING)),
        RuntimeSettingsGroups.SECURITY_BUBBLES to Group(setOf("com.miui.securitycenter", "com.miui.securitycore", "com.android.systemui"), strict = setOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_BUBBLE_NOTIFICATION_UNLOCK)),
        RuntimeSettingsGroups.SECURITY_CLIPBOARD to Group(setOf("com.miui.securitycenter", "com.lbe.security.miui"), strict = setOf(PlatformSettingsKeys.KEY_LBE_CLIPBOARD_TOAST)),
        RuntimeSettingsGroups.SECURITY_APP_LOCK to Group(setOf("com.miui.securitycenter"), strict = setOf(PlatformSettingsKeys.KEY_SECURITY_CENTER_APP_LOCK_SCRAMBLE_PIN)),
        RuntimeSettingsGroups.BARRAGE to Group(setOf("com.xiaomi.barrage"), strict = setOf(PlatformSettingsKeys.KEY_BARRAGE_ANY_APP)),
        RuntimeSettingsGroups.BUSINESS_HALL_ADS to Group(setOf("com.android.contacts"), strict = setOf(PlatformSettingsKeys.KEY_CONTACTS_BUSINESS_HALL_AD_BLOCK)),
        // The Network Assistant classes are packaged in Security Center; its activities run in
        // the main process and its service/provider consumers also use the .remote process.
        RuntimeSettingsGroups.OFFLINE_CONFIG to Group(setOf("com.miui.securitycenter", "com.android.contacts"), strict = setOf(PlatformSettingsKeys.KEY_NETWORK_ASSISTANT_IGNORE_OFFLINE, PlatformSettingsKeys.KEY_CONTACTS_BUSINESS_HALL_IGNORE_OFFLINE)),
        RuntimeSettingsGroups.WIFI_DETAILS to Group(setOf("com.android.settings"), keys = setOf(PlatformSettingsKeys.KEY_WIFI_SAVED_NETWORKS_ENTRY_POSITION), strict = setOf(PlatformSettingsKeys.KEY_WIFI_DETAIL_IP_MULTILINE, PlatformSettingsKeys.KEY_WIFI_SAVED_NETWORK_CUSTOM_ACTIONS))
    )
}
