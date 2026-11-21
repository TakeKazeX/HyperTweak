package com.takekazex.hypertweak.ui.app

import androidx.compose.runtime.*
import com.takekazex.hypertweak.settings.contract.AodSettingsKeys
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.AppSettingsStore
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

/** Activity-scoped observable settings; initial reads and service rebinds share this owner. */
internal class AppSettingsState(val storage: AppSettingsStore) {
    private val settings = com.takekazex.hypertweak.ui.state.ReloadableSettings()

    /**
     * Runs before every persisted write with the key and new value, while storage still holds the
     * old one. [AppRestartTracker] installs its restart-baseline tracking here; keys without a
     * restart scope are ignored there.
     */
    var beforeWrite: (key: String, value: Any) -> Unit = { _, _ -> }

    /** Assigning the property persists it (after [beforeWrite]); reload never writes. */
    private fun <T : Any> persisted(key: String, put: (String, T) -> Unit, read: () -> T) =
        settings.preference(write = { value: T -> beforeWrite(key, value); put(key, value) }, read = read)
    var appLanguage by persisted(AppSettingsKeys.KEY_LANGUAGE, storage::putInt) { storage.getInt(AppSettingsKeys.KEY_LANGUAGE, 0) }
    var appBlurMode by persisted(AppSettingsKeys.KEY_APP_BLUR_MODE, storage::putInt) { storage.getInt(AppSettingsKeys.KEY_APP_BLUR_MODE, 1).coerceIn(0, 2) }
    var appUiStyle by persisted(AppSettingsKeys.KEY_APP_UI_STYLE, storage::putInt) { storage.getInt(AppSettingsKeys.KEY_APP_UI_STYLE, AppSettingsKeys.DEFAULT_APP_UI_STYLE).coerceIn(0, 1) }
    var themeMode by persisted(AppSettingsKeys.KEY_THEME_MODE, storage::putInt) { storage.getInt(AppSettingsKeys.KEY_THEME_MODE, 0) }
    var useMonet by persisted(AppSettingsKeys.KEY_USE_MONET, storage::putBoolean) { storage.getBoolean(AppSettingsKeys.KEY_USE_MONET, false) }
    var seedColorHex by persisted(AppSettingsKeys.KEY_SEED_COLOR, storage::putInt) { storage.getInt(AppSettingsKeys.KEY_SEED_COLOR, AppSettingsKeys.DEFAULT_SEED_COLOR) }
    var paletteStyle by persisted(AppSettingsKeys.KEY_THEME_PALETTE_STYLE, storage::putInt) { storage.getInt(AppSettingsKeys.KEY_THEME_PALETTE_STYLE, 0) }
    var pureBlackDarkTheme by persisted(AppSettingsKeys.KEY_PURE_BLACK_DARK_THEME, storage::putBoolean) { storage.getBoolean(AppSettingsKeys.KEY_PURE_BLACK_DARK_THEME, false) }
    var useFloatingBottomBar by persisted(PlatformSettingsKeys.KEY_USE_FLOATING_BOTTOM_BAR, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_USE_FLOATING_BOTTOM_BAR, false) }
    var floatingBarStyle by persisted(PlatformSettingsKeys.KEY_FLOATING_BAR_STYLE, storage::putInt) { storage.getInt(PlatformSettingsKeys.KEY_FLOATING_BAR_STYLE, 0) }
    var predictiveBackStyle by persisted(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_STYLE, storage::putInt) { storage.getInt(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_STYLE, 1) }
    var predictiveBackFollowGesture by persisted(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, true) }
    var allowLandscape by persisted(PlatformSettingsKeys.KEY_ALLOW_LANDSCAPE, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_ALLOW_LANDSCAPE, false) }
    var pageScale by persisted(AppSettingsKeys.KEY_PAGE_SCALE, storage::putFloat) { storage.getFloat(AppSettingsKeys.KEY_PAGE_SCALE, 1.0f) }
    var aodFullscreen by persisted(AodSettingsKeys.KEY_AOD_FULLSCREEN, storage::putBoolean) { storage.getBoolean(AodSettingsKeys.KEY_AOD_FULLSCREEN, false) }
    var removeGms by persisted(PlatformSettingsKeys.KEY_REMOVE_GMS_RESTRICTION, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_REMOVE_GMS_RESTRICTION, false) }
    var blockDownloadXlLogDir by persisted(PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, false) }
    var downloadAlwaysShowFullLink by persisted(PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK, false) }
    var downloadHideXl by persisted(PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL, false) }
    var downloadAddNewButton by persisted(PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON, false) }
    var downloadFocusNotification by persisted(PlatformSettingsKeys.KEY_DOWNLOAD_FOCUS_NOTIFICATION, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_FOCUS_NOTIFICATION, false) }
    var fullScreenTranslate by persisted(PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE, false) }
    var askAboutScreen by persisted(PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN, false) }
    var hideFingerprintAod by persisted(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD, storage::putBoolean) { storage.fingerprint.hideFingerprintAodEnabled() }
    var hideFingerprintLockscreen by persisted(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN, storage::putBoolean) { storage.fingerprint.hideFingerprintLockscreenEnabled() }
    var hideFingerprintAppAuth by persisted(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH, storage::putBoolean) { storage.fingerprint.hideFingerprintAppAuthEnabled() }
    var hideLockscreenStatusBar by persisted(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false) }
    var notificationHeaderClockSeconds by persisted(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS, false) }
    var statusBarClockSeconds by persisted(SystemUiSettingsKeys.KEY_STATUS_BAR_CLOCK_SECONDS, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_STATUS_BAR_CLOCK_SECONDS, false) }
    var notificationMonetTextColor by persisted(SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR, false) }
    var notificationAbsoluteTime by persisted(SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME, false) }
    var notificationFontWeight by persisted(SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT, false) }
    var lockscreenFingerprintAvoid by persisted(SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID, storage::putInt) {
        storage.getInt(
            SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID,
            PlatformSettingsKeys.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT
        )
    }
    var showInSettings by persisted(PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS, false) }
    var hideGestureBar by persisted(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR, false) }
    var gestureBarRaiseLayout by persisted(SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT, false) }
    var hideGestureBarRecents by persisted(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_RECENTS, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_RECENTS, false) }
    var hideGestureBarLauncher by persisted(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_LAUNCHER, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_LAUNCHER, false) }
    var gestureBarAttachTransition by persisted(SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION, false) }
    var powerButtonAction by settings.preference(write = { storage.powerButton.setPowerButtonAction(it) }) { storage.powerButton.powerButtonAction() }
    var contextualSearchHaptics by persisted(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_HAPTICS, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_HAPTICS, false) }
    var contextualSearchLongPress by persisted(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS, storage::putBoolean) { storage.backGesture.contextualSearchLongPress() }
    var hideLauncherIcon by settings.preference { storage.getBoolean(AppSettingsKeys.KEY_HIDE_LAUNCHER_ICON, false) }
    var sliderDisplayMode by persisted(SystemUiSettingsKeys.KEY_SLIDER_DISPLAY_MODE, storage::putInt) { storage.getInt(SystemUiSettingsKeys.KEY_SLIDER_DISPLAY_MODE, (if (storage.getBoolean(SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE, false)) 3 else 0)).coerceIn(0, 3) }
    var sliderValueMode by persisted(SystemUiSettingsKeys.KEY_SLIDER_VALUE_MODE, storage::putInt) { storage.getInt(SystemUiSettingsKeys.KEY_SLIDER_VALUE_MODE, 2).coerceIn(0, 2) }
    var sliderPopupStyle by persisted(SystemUiSettingsKeys.KEY_SLIDER_POPUP_STYLE, storage::putInt) { storage.getInt(SystemUiSettingsKeys.KEY_SLIDER_POPUP_STYLE, 0).coerceIn(0, 2) }
    var sliderPopupPosition by persisted(SystemUiSettingsKeys.KEY_SLIDER_POPUP_POSITION, storage::putInt) { storage.getInt(SystemUiSettingsKeys.KEY_SLIDER_POPUP_POSITION, 1).coerceIn(0, 1) }
    var sliderSamePercentageStyle by persisted(SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false) }
    var ccEditEnabled by persisted(SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED, false) }
    var paModelSpoofEnabled by persisted(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF, false) }
    var mediaCardHideAppIcon by persisted(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_APP_ICON, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_APP_ICON, false) }
    var mediaCardHideDeviceSwitch by persisted(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, false) }
    var mediaCardSwipe by persisted(SystemUiSettingsKeys.KEY_MEDIA_CARD_SWIPE, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_SWIPE, false) }
    var mediaCardCarouselStyle by persisted(SystemUiSettingsKeys.KEY_MEDIA_CARD_CAROUSEL_STYLE, storage::putInt) { storage.getInt(SystemUiSettingsKeys.KEY_MEDIA_CARD_CAROUSEL_STYLE, -1) }
    var lockscreenAllNotifications by persisted(SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, false) }
    var lockscreenKeepNotifications by persisted(SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, false) }
    var unlockPasskey by persisted(PlatformSettingsKeys.KEY_UNLOCK_PASSKEY, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_UNLOCK_PASSKEY, false) }
    var unlockThirdPartyDarkMode by persisted(PlatformSettingsKeys.KEY_UNLOCK_THIRD_PARTY_DARK_MODE, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_UNLOCK_THIRD_PARTY_DARK_MODE, false) }
    var disableSpatialAudio by persisted(PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO, false) }
    var forceAdaptiveAnc by persisted(PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC, false) }
    var powerKeeperBlockWhitelistRestore by persisted(PlatformSettingsKeys.KEY_POWERKEEPER_BLOCK_WHITELIST_RESTORE, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_POWERKEEPER_BLOCK_WHITELIST_RESTORE, false) }
    var fcmLiveEnabled by persisted(PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED, false) }
    var lbeClipboardToast by persisted(PlatformSettingsKeys.KEY_LBE_CLIPBOARD_TOAST, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_LBE_CLIPBOARD_TOAST, false) }
    var disableMiTrustRiskMonitoring by persisted(PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING, false) }
    var disableGuardEnvironmentCheck by persisted(PlatformSettingsKeys.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK, storage::putBoolean) {
        storage.getBoolean(
            PlatformSettingsKeys.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK,
            false
        )
    }
    var blockGuardUploadAppList by persisted(PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST, storage::putBoolean) {
        storage.getBoolean(
            PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST,
            false
        )
    }
    var blockMiLinkHpplayFiles by persisted(PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES, false) }
    var allowThirdPartyTheme by persisted(PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME, false) }
    var removeIslandPlaceholder by persisted(PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER, false) }
    var threeIslands by persisted(PlatformSettingsKeys.KEY_THREE_ISLANDS, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_THREE_ISLANDS, false) }
    var landscapeFullIslands by persisted(PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS, false) }
    var landscapeMoreIslands by persisted(PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS, false) }
    var ccHideSuperIsland by persisted(SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND, storage::putBoolean) { storage.getBoolean(SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND, false) }
    var islandLiveUpdateIcon by persisted(PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON, false) }
    var islandNarrowTimeFont by persisted(PlatformSettingsKeys.KEY_ISLAND_NARROW_TIME_FONT, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_ISLAND_NARROW_TIME_FONT, false) }
    var islandPullDownStretch by persisted(PlatformSettingsKeys.KEY_ISLAND_PULL_DOWN_STRETCH, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_ISLAND_PULL_DOWN_STRETCH, false) }
    var islandLottieResume by persisted(PlatformSettingsKeys.KEY_ISLAND_LOTTIE_RESUME, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_ISLAND_LOTTIE_RESUME, false) }
    var islandFlashlightControl by persisted(PlatformSettingsKeys.KEY_ISLAND_FLASHLIGHT_CONTROL, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_ISLAND_FLASHLIGHT_CONTROL, false) }
    var focusNotificationUnlockWhitelist by persisted(PlatformSettingsKeys.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, false) }
    var mediaSuperIslandUnlockWhitelist by persisted(PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST, false) }
    var xmsfUnlockFocusAuth by persisted(PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH, false) }
    var showGoogleServicesInSettings by persisted(PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS, storage::putBoolean) {
        storage.getBoolean(
            PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS,
            false
        )
    }
    var settingsGlobalInterface by persisted(PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE, false) }
    var disableVideoRingback by persisted(PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK, storage::putBoolean) { storage.getBoolean(PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK, false) }
    var immediateMonetRefresh by persisted(SystemUiSettingsKeys.KEY_IMMEDIATE_MONET_REFRESH, storage::putBoolean) {
        storage.getBoolean(
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
            PlatformSettingsKeys.KEY_DOWNLOAD_FOCUS_NOTIFICATION -> downloadFocusNotification
            SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD -> hideFingerprintAod
            SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN -> hideFingerprintLockscreen
            SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH -> hideFingerprintAppAuth
            SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR -> hideLockscreenStatusBar
            SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS -> lockscreenAllNotifications
            SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS -> lockscreenKeepNotifications
            SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS -> notificationHeaderClockSeconds
            SystemUiSettingsKeys.KEY_STATUS_BAR_CLOCK_SECONDS -> statusBarClockSeconds
            SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR -> notificationMonetTextColor
            SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT -> notificationFontWeight
            SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME -> notificationAbsoluteTime
            PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR -> hideGestureBar
            SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT -> gestureBarRaiseLayout
            SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE -> sliderSamePercentageStyle
            PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS -> showInSettings
            PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS -> showGoogleServicesInSettings
            PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE -> settingsGlobalInterface
            PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK -> disableVideoRingback
            PlatformSettingsKeys.KEY_UNLOCK_PASSKEY -> unlockPasskey
            PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO -> disableSpatialAudio
            PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC -> forceAdaptiveAnc
            PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED -> fcmLiveEnabled
            PlatformSettingsKeys.KEY_POWERKEEPER_BLOCK_WHITELIST_RESTORE -> powerKeeperBlockWhitelistRestore
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
            PlatformSettingsKeys.KEY_ISLAND_PULL_DOWN_STRETCH -> islandPullDownStretch
            PlatformSettingsKeys.KEY_ISLAND_LOTTIE_RESUME -> islandLottieResume
            PlatformSettingsKeys.KEY_ISLAND_FLASHLIGHT_CONTROL -> islandFlashlightControl
            PlatformSettingsKeys.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST -> focusNotificationUnlockWhitelist
            PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST -> mediaSuperIslandUnlockWhitelist
            PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH -> xmsfUnlockFocusAuth
            PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE -> fullScreenTranslate
            PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN -> askAboutScreen
            else -> storage.getBoolean(key, false)
        }
    }

    /** Int-valued counterpart of [currentTweakValue] for restart-scope tracked selectors. */
    fun currentTweakValueInt(key: String): Int {
        return when (key) {
            SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID -> lockscreenFingerprintAvoid
            else -> storage.getInt(key, PlatformSettingsKeys.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT)
        }
    }

    fun reload() = settings.reload()
}
