package com.takekazex.hypertweak.ui.navigation

import androidx.compose.animation.*
import androidx.compose.runtime.*
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.page.MainPagerScreen
import top.yukonga.miuix.kmp.nav.core.*

internal fun NavEntryBuilder.mainPages(settings: NavigationSettings, navController: NavController, mainPagerState: com.takekazex.hypertweak.ui.liquid.MainPagerState) {
    with(settings) {
        entry<Route.Main> {
            MainPagerScreen(
                mainPagerState = mainPagerState,
                useFloatingBottomBar = useFloatingBottomBar,
                floatingBarStyle = floatingBarStyle,
                backdrop = backdrop,
                moduleActive = moduleActive,
                hotReloadAvailable = hotReloadAvailable,
                hotReloading = hotReloading,
                hotReloadTargets = hotReloadTargets,
                hotReloadReport = hotReloadReport,
                pendingRestartScopes = pendingRestartScopes,
                paModelSpoofEnabled = paModelSpoofEnabled,
                onPaModelSpoofEnabledChange = onPaModelSpoofEnabledChange,
                onNavigateToSystemLauncher = { navController.navigateTo(Route.SystemLauncher) },
                onNavigateToScreenshot = { navController.navigateTo(Route.Screenshot) },
                onNavigateToSystemUi = {
                    navController.navigateTo(Route.SystemUi)
                },
                onNavigateToDownloadManager = {
                    navController.navigateTo(Route.DownloadManager)
                },
                onNavigateToSuperIsland = { navController.navigateTo(Route.SuperIsland) },
                onNavigateToPurification = { navController.navigateTo(Route.Purification) },
                onNavigateToSecurityCenter = {
                    navController.navigateTo(Route.SecurityCenter)
                },
                showInSettings = showInSettings,
                onShowInSettingsChange = onShowInSettingsChange,
                hideLauncherIcon = hideLauncherIcon,
                onHideLauncherIconChange = onHideLauncherIconChange,
                 unlockThirdPartyDarkMode = unlockThirdPartyDarkMode,
                 onUnlockThirdPartyDarkModeChange = onUnlockThirdPartyDarkModeChange,
                disableSpatialAudio = disableSpatialAudio,
                onDisableSpatialAudioChange = onDisableSpatialAudioChange,
                forceAdaptiveAnc = forceAdaptiveAnc,
                onForceAdaptiveAncChange = onForceAdaptiveAncChange,
                allowThirdPartyTheme = allowThirdPartyTheme,
                onAllowThirdPartyThemeChange = onAllowThirdPartyThemeChange,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useMonet = useMonet,
                onUseMonetChange = onUseMonetChange,
                seedColorHex = seedColorHex,
                onSeedColorChange = onSeedColorChange,
                onUseFloatingBottomBarChange = onUseFloatingBottomBarChange,
                onFloatingBarStyleChange = onFloatingBarStyleChange,
                predictiveBackStyle = predictiveBackStyle,
                onPredictiveBackStyleChange = onPredictiveBackStyleChange,
                predictiveBackFollowGesture = predictiveBackFollowGesture,
                onPredictiveBackFollowGestureChange = onPredictiveBackFollowGestureChange,
                allowLandscape = allowLandscape,
                onAllowLandscapeChange = onAllowLandscapeChange,
                pageScale = pageScale,
                onPageScaleChange = onPageScaleChange,
                onNavigateToAbout = {
                    navController.navigateTo(Route.About)
                },
                onNavigateToAppearance = {
                    navController.navigateTo(Route.Appearance)
                },
                onNavigateToScopePrompts = {
                    navController.navigateTo(Route.ScopePrompts)
                },
                onNavigateToBackupRestore = {
                    navController.navigateTo(Route.BackupRestore)
                },
                onNavigateToHiddenFeatures = {
                    navController.navigateTo(Route.HiddenFeatures)
                },
                onNavigateToAppShortcuts = {
                    navController.navigateTo(Route.AppShortcuts)
                },
                onNavigateToAospRestore = {
                    navController.navigateTo(Route.AospRestore)
                },
                onNavigateToGoogleServices = {
                    navController.navigateTo(Route.GoogleServices)
                },
                onNavigateToIconTuner = {
                    navController.navigateTo(Route.IconTuner)
                },
                onNavigateToGlassTuner = {
                    navController.navigateTo(Route.GlassTuner)
                },
                onNavigateToCameraWatermark = {
                    navController.navigateTo(Route.CameraWatermark)
                },
                onNavigateToExperimentalFeatures = {
                    navController.navigateTo(Route.ExperimentalFeatures)
                },
                onNavigateToControlCenterCorner = {
                    navController.navigateTo(Route.ControlCenterCorner)
                },
                onNavigateToControlCenterResize = {
                    navController.navigateTo(Route.ControlCenterResize)
                },
                onNavigateToDeviceInfo = {
                    navController.navigateTo(Route.DeviceInfo)
                },
                onNavigateToBatteryInfo = {
                    navController.navigateTo(Route.BatteryInfo)
                },
                onHotReload = onHotReload,
                onRestartAllScopes = onRestartAllScopes,
                onRestartScope = onRestartScope,
                snackbarHostState = snackbarHostState,
                appLanguage = appLanguage,
                onAppLanguageChange = onAppLanguageChange
            )
        }

    }
}
