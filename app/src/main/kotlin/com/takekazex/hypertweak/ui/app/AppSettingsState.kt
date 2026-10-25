package com.takekazex.hypertweak.ui.app

import androidx.compose.runtime.*
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.settings.contract.AodSettingsKeys
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

/** Activity-scoped observable settings; initial reads and service rebinds share this owner. */
internal class AppSettingsState {
    private val settings = com.takekazex.hypertweak.ui.state.ReloadableSettings()
    var appLanguage by settings.preference { Preferences.getInt(AppSettingsKeys.KEY_LANGUAGE, 0) }
    var appBlurMode by settings.preference { Preferences.getInt(AppSettingsKeys.KEY_APP_BLUR_MODE, 1).coerceIn(0, 2) }
    var themeMode by settings.preference { Preferences.getInt(AppSettingsKeys.KEY_THEME_MODE, 0) }
    var useMonet by settings.preference { Preferences.getBoolean(AppSettingsKeys.KEY_USE_MONET, false) }
    var seedColorHex by settings.preference { Preferences.getInt(AppSettingsKeys.KEY_SEED_COLOR, AppSettingsKeys.DEFAULT_SEED_COLOR) }
    var paletteStyle by settings.preference { Preferences.getInt(AppSettingsKeys.KEY_THEME_PALETTE_STYLE, 0) }
    var pureBlackDarkTheme by settings.preference { Preferences.getBoolean(AppSettingsKeys.KEY_PURE_BLACK_DARK_THEME, false) }
    var useFloatingBottomBar by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_USE_FLOATING_BOTTOM_BAR, false) }
    var floatingBarStyle by settings.preference { Preferences.getInt(PlatformSettingsKeys.KEY_FLOATING_BAR_STYLE, 0) }
    var predictiveBackStyle by settings.preference { Preferences.getInt(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_STYLE, 1) }
    var predictiveBackFollowGesture by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, true) }
    var allowLandscape by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_ALLOW_LANDSCAPE, false) }
    var pageScale by settings.preference { Preferences.getFloat(AppSettingsKeys.KEY_PAGE_SCALE, 1.0f) }
    var aodFullscreen by settings.preference { Preferences.getBoolean(AodSettingsKeys.KEY_AOD_FULLSCREEN, false) }
    var removeGms by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_REMOVE_GMS_RESTRICTION, false) }
    var blockDownloadXlLogDir by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, false) }
    var downloadAlwaysShowFullLink by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK, false) }
    var downloadHideXl by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL, false) }
    var downloadAddNewButton by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON, false) }
    var quickShareEnabled by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_QUICK_SHARE_ENABLED, false) }
    var fullScreenTranslate by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE, false) }
    var askAboutScreen by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN, false) }
    var hideFingerprintAod by settings.preference { Preferences.hideFingerprintAodEnabled() }
    var hideFingerprintLockscreen by settings.preference { Preferences.hideFingerprintLockscreenEnabled() }
    var hideFingerprintAppAuth by settings.preference { Preferences.hideFingerprintAppAuthEnabled() }
    var hideLockscreenStatusBar by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false) }
    var notificationHeaderClockSeconds by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS, false) }
    var notificationMonetTextColor by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR, false) }
    var notificationAbsoluteTime by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME, false) }
    var notificationFontWeight by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT, false) }
    var lockscreenFingerprintAvoid by settings.preference {
        Preferences.getInt(
            SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID,
            PlatformSettingsKeys.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT
        )
    }
    var showInSettings by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS, false) }
    var hideGestureBar by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR, false) }
    var gestureBarRaiseLayout by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT, false) }
    var hideGestureBarRecents by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_RECENTS, false) }
    var hideGestureBarLauncher by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_LAUNCHER, false) }
    var gestureBarAttachTransition by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION, false) }
    var powerButtonAction by settings.preference { Preferences.powerButtonAction() }
    var contextualSearchHaptics by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_HAPTICS, false) }
    var contextualSearchLongPress by settings.preference { Preferences.contextualSearchLongPress() }
    var hideLauncherIcon by settings.preference { Preferences.getBoolean(AppSettingsKeys.KEY_HIDE_LAUNCHER_ICON, false) }
    var sliderShowPercentage by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE, false) }
    var sliderSamePercentageStyle by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false) }
    var ccEditEnabled by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED, false) }
    var paModelSpoofEnabled by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF, false) }
    var mediaCardHideAppIcon by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_APP_ICON, false) }
    var mediaCardHideDeviceSwitch by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, false) }
    var lockscreenAllNotifications by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, false) }
    var lockscreenKeepNotifications by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, false) }
    var unlockPasskey by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_UNLOCK_PASSKEY, false) }
    var unlockThirdPartyDarkMode by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_UNLOCK_THIRD_PARTY_DARK_MODE, false) }
    var disableSpatialAudio by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO, false) }
    var forceAdaptiveAnc by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC, false) }
    var fcmLiveEnabled by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED, false) }
    var lbeClipboardToast by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_LBE_CLIPBOARD_TOAST, false) }
    var disableMiTrustRiskMonitoring by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING, false) }
    var disableGuardEnvironmentCheck by settings.preference {
        Preferences.getBoolean(
            PlatformSettingsKeys.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK,
            false
        )
    }
    var blockGuardUploadAppList by settings.preference {
        Preferences.getBoolean(
            PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST,
            false
        )
    }
    var blockMiLinkHpplayFiles by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES, false) }
    var allowThirdPartyTheme by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME, false) }
    var removeIslandPlaceholder by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER, true) }
    var threeIslands by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_THREE_ISLANDS, false) }
    var landscapeFullIslands by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS, false) }
    var landscapeMoreIslands by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS, false) }
    var ccHideSuperIsland by settings.preference { Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND, false) }
    var islandLiveUpdateIcon by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON, false) }
    var islandNarrowTimeFont by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_ISLAND_NARROW_TIME_FONT, false) }
    var focusNotificationUnlockWhitelist by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, false) }
    var mediaSuperIslandUnlockWhitelist by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST, false) }
    var xmsfUnlockFocusAuth by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH, false) }
    var showGoogleServicesInSettings by settings.preference {
        Preferences.getBoolean(
            PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS,
            false
        )
    }
    var settingsGlobalInterface by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE, false) }
    var disableVideoRingback by settings.preference { Preferences.getBoolean(PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK, false) }
    var immediateMonetRefresh by settings.preference {
        Preferences.getBoolean(
            SystemUiSettingsKeys.KEY_IMMEDIATE_MONET_REFRESH,
            SystemUiSettingsKeys.DEFAULT_IMMEDIATE_MONET_REFRESH
        )
    }

    fun currentTweakValue(key: String): Boolean {
        return when (key) {
            AodSettingsKeys.KEY_AOD_FULLSCREEN -> aodFullscreen
            PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR -> blockDownloadXlLogDir
            PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK -> downloadAlwaysShowFullLink
            PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL -> downloadHideXl
            PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON -> downloadAddNewButton
            SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD -> hideFingerprintAod
            SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN -> hideFingerprintLockscreen
            SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH -> hideFingerprintAppAuth
            SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR -> hideLockscreenStatusBar
            SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS -> notificationHeaderClockSeconds
            SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR -> notificationMonetTextColor
            SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT -> notificationFontWeight
            SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME -> notificationAbsoluteTime
            PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR -> hideGestureBar
            SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT -> gestureBarRaiseLayout
            SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE -> sliderShowPercentage
            SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE -> sliderSamePercentageStyle
            PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS -> showInSettings
            PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS -> showGoogleServicesInSettings
            PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE -> settingsGlobalInterface
            PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK -> disableVideoRingback
            PlatformSettingsKeys.KEY_UNLOCK_PASSKEY -> unlockPasskey
            PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO -> disableSpatialAudio
            PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC -> forceAdaptiveAnc
            PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED -> fcmLiveEnabled
            PlatformSettingsKeys.KEY_LBE_CLIPBOARD_TOAST -> lbeClipboardToast
            PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING -> disableMiTrustRiskMonitoring
            PlatformSettingsKeys.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK -> disableGuardEnvironmentCheck
            PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST -> blockGuardUploadAppList
            PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES -> blockMiLinkHpplayFiles
            PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME -> allowThirdPartyTheme
            PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER -> removeIslandPlaceholder
            PlatformSettingsKeys.KEY_THREE_ISLANDS -> threeIslands
            PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS -> landscapeFullIslands
            PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS -> landscapeMoreIslands
            SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND -> ccHideSuperIsland
            PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON -> islandLiveUpdateIcon
            PlatformSettingsKeys.KEY_ISLAND_NARROW_TIME_FONT -> islandNarrowTimeFont
            PlatformSettingsKeys.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST -> focusNotificationUnlockWhitelist
            PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST -> mediaSuperIslandUnlockWhitelist
            PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH -> xmsfUnlockFocusAuth
            PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE -> fullScreenTranslate
            PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN -> askAboutScreen
            else -> Preferences.getBoolean(key, false)
        }
    }

    /** Int-valued counterpart of [currentTweakValue] for restart-scope tracked selectors. */
    fun currentTweakValueInt(key: String): Int {
        return when (key) {
            SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID -> lockscreenFingerprintAvoid
            else -> Preferences.getInt(key, PlatformSettingsKeys.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT)
        }
    }

    fun reload() = settings.reload()
}
