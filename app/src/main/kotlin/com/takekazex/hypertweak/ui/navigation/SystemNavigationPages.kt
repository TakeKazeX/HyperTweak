package com.takekazex.hypertweak.ui.navigation

import androidx.compose.animation.*
import androidx.compose.runtime.*
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.page.AodIconsPage
import com.takekazex.hypertweak.ui.page.SystemUIPage
import com.takekazex.hypertweak.ui.page.NotificationPage
import com.takekazex.hypertweak.ui.page.MediaCardPage
import com.takekazex.hypertweak.ui.page.NotificationHeaderPage
import com.takekazex.hypertweak.ui.page.PrivacyIndicatorPage
import com.takekazex.hypertweak.ui.page.IconTunerPage
import com.takekazex.hypertweak.ui.page.IconOrderPage
import com.takekazex.hypertweak.ui.page.ControlCenterIconRowsPage
import com.takekazex.hypertweak.ui.page.SuperIslandPage
import top.yukonga.miuix.kmp.nav.core.*

internal fun NavEntryBuilder.systemPages(settings: NavigationSettings, navController: NavController) {
    with(settings) {
        entry<Route.PrivacyIndicator> {
            PrivacyIndicatorPage(onBack = { navController.pop() })
        }
        entry<Route.IconTuner> {
            IconTunerPage(
                onBack = { navController.pop() },
                onNavigateToIconOrder = { navController.navigateTo(Route.IconOrder) },
                onNavigateToIconRows = { navController.navigateTo(Route.ControlCenterIconRows) }
            )
        }
        entry<Route.ControlCenterIconRows> {
            ControlCenterIconRowsPage(onBack = { navController.pop() })
        }
        entry<Route.IconOrder> {
            IconOrderPage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.SystemUi> {
            SystemUIPage(
                onBack = { navController.pop() },
                immediateMonetRefresh = immediateMonetRefresh,
                onImmediateMonetRefreshChange = onImmediateMonetRefreshChange,
                aodFullscreen = aodFullscreen,
                onAodFullscreenChange = onAodFullscreenChange,
                hideFingerprintAod = hideFingerprintAod,
                onHideFingerprintAodChange = onHideFingerprintAodChange,
                hideFingerprintLockscreen = hideFingerprintLockscreen,
                onHideFingerprintLockscreenChange = onHideFingerprintLockscreenChange,
                hideFingerprintAppAuth = hideFingerprintAppAuth,
                onHideFingerprintAppAuthChange = onHideFingerprintAppAuthChange,
                hideLockscreenStatusBar = hideLockscreenStatusBar,
                onHideLockscreenStatusBarChange = onHideLockscreenStatusBarChange,
                lockscreenFingerprintAvoid = lockscreenFingerprintAvoid,
                onLockscreenFingerprintAvoidChange = onLockscreenFingerprintAvoidChange,
                onNavigateToChargingDetail = {
                    navController.navigateTo(Route.ChargingDetail)
                },
                onNavigateToLockscreenBottomText = {
                    navController.navigateTo(Route.LockscreenBottomText)
                },
                lockscreenAllNotifications = lockscreenAllNotifications,
                onLockscreenAllNotificationsChange = onLockscreenAllNotificationsChange,
                lockscreenKeepNotifications = lockscreenKeepNotifications,
                onLockscreenKeepNotificationsChange = onLockscreenKeepNotificationsChange,
                sliderShowPercentage = sliderShowPercentage,
                onSliderShowPercentageChange = onSliderShowPercentageChange,
                sliderSamePercentageStyle = sliderSamePercentageStyle,
                onSliderSamePercentageChange = onSliderSamePercentageChange,
                hideGestureBar = hideGestureBar,
                onHideGestureBarChange = onHideGestureBarChange,
                gestureBarRaiseLayout = gestureBarRaiseLayout,
                onGestureBarRaiseLayoutChange = onGestureBarRaiseLayoutChange,
                hideGestureBarRecents = hideGestureBarRecents,
                onHideGestureBarRecentsChange = onHideGestureBarRecentsChange,
                hideGestureBarLauncher = hideGestureBarLauncher,
                onHideGestureBarLauncherChange = onHideGestureBarLauncherChange,
                gestureBarAttachTransition = gestureBarAttachTransition,
                onGestureBarAttachTransitionChange = onGestureBarAttachTransitionChange,
                powerButtonAction = powerButtonAction,
                onPowerButtonActionChange = onPowerButtonActionChange,
                contextualSearchHaptics = contextualSearchHaptics,
                onContextualSearchHapticsChange = onContextualSearchHapticsChange,
                contextualSearchLongPress = contextualSearchLongPress,
                onContextualSearchLongPressChange = onContextualSearchLongPressChange,
                onNavigateToNotifications = { navController.navigateTo(Route.Notifications) },
                onNavigateToMediaCard = { navController.navigateTo(Route.MediaCard) },
                onNavigateToIconTuner = {
                    navController.navigateTo(Route.IconTuner)
                },
                onNavigateToPrivacyIndicator = { navController.navigateTo(Route.PrivacyIndicator) },
                onNavigateToAodIcons = { navController.navigateTo(Route.AodIcons) }
            )
        }
        entry<Route.AodIcons> {
            AodIconsPage(onBack = { navController.pop() })
        }
        entry<Route.Notifications> {
            NotificationPage(
                onBack = { navController.pop() },
                notificationHeaderClockSeconds = notificationHeaderClockSeconds,
                onNotificationHeaderClockSecondsChange = onNotificationHeaderClockSecondsChange,
                notificationMonetTextColor = notificationMonetTextColor,
                onNotificationMonetTextColorChange = onNotificationMonetTextColorChange,
                notificationFontWeight = notificationFontWeight,
                onNotificationFontWeightChange = onNotificationFontWeightChange,
                notificationAbsoluteTime = notificationAbsoluteTime,
                onNotificationAbsoluteTimeChange = onNotificationAbsoluteTimeChange,
                onNavigateToNotificationHeader = { navController.navigateTo(Route.NotificationHeader) },
            )
        }
        entry<Route.MediaCard> {
            MediaCardPage(onBack = { navController.pop() },
                mediaCardHideAppIcon = mediaCardHideAppIcon,
                onMediaCardHideAppIconChange = onMediaCardHideAppIconChange,
                mediaCardHideDeviceSwitch = mediaCardHideDeviceSwitch,
                onMediaCardHideDeviceSwitchChange = onMediaCardHideDeviceSwitchChange,
            )
        }
        entry<Route.NotificationHeader> {
            NotificationHeaderPage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.SuperIsland> {
            SuperIslandPage(
                onBack = { navController.pop() },
                removeIslandPlaceholder = removeIslandPlaceholder,
                onRemoveIslandPlaceholderChange = onRemoveIslandPlaceholderChange,
                threeIslands = threeIslands,
                landscapeFullIslands = landscapeFullIslands,
                landscapeMoreIslands = landscapeMoreIslands,
                ccHideSuperIsland = ccHideSuperIsland,
                islandLiveUpdateIcon = islandLiveUpdateIcon,
                islandNarrowTimeFont = islandNarrowTimeFont,
                onThreeIslandsChange = onThreeIslandsChange,
                onLandscapeFullIslandsChange = onLandscapeFullIslandsChange,
                onLandscapeMoreIslandsChange = onLandscapeMoreIslandsChange,
                onCcHideSuperIslandChange = onCcHideSuperIslandChange,
                onIslandLiveUpdateIconChange = onIslandLiveUpdateIconChange,
                onIslandNarrowTimeFontChange = onIslandNarrowTimeFontChange,
                focusNotificationUnlockWhitelist = focusNotificationUnlockWhitelist,
                onFocusNotificationUnlockWhitelistChange = onFocusNotificationUnlockWhitelistChange,
                mediaSuperIslandUnlockWhitelist = mediaSuperIslandUnlockWhitelist,
                onMediaSuperIslandUnlockWhitelistChange = onMediaSuperIslandUnlockWhitelistChange,
                xmsfUnlockFocusAuth = xmsfUnlockFocusAuth,
                onXmsfUnlockFocusAuthChange = onXmsfUnlockFocusAuthChange,
            )
        }

    }
}
