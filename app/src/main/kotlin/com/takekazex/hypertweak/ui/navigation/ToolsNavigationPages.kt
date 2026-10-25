package com.takekazex.hypertweak.ui.navigation

import androidx.compose.animation.*
import androidx.compose.runtime.*
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.page.AboutPage
import com.takekazex.hypertweak.ui.page.CreditsPage
import com.takekazex.hypertweak.ui.page.HiddenFeaturesPage
import com.takekazex.hypertweak.ui.page.AppShortcutsPage
import com.takekazex.hypertweak.ui.page.DebugPage
import com.takekazex.hypertweak.ui.page.DeveloperSettingsPage
import com.takekazex.hypertweak.ui.page.BatteryInfoPage
import com.takekazex.hypertweak.ui.page.DeviceInfoPage
import com.takekazex.hypertweak.ui.page.LogsPage
import com.takekazex.hypertweak.ui.page.AppearancePage
import com.takekazex.hypertweak.ui.page.ScopePromptsPage
import com.takekazex.hypertweak.ui.page.BackupRestorePage
import com.takekazex.hypertweak.ui.page.UpdatePage
import top.yukonga.miuix.kmp.nav.core.*

internal fun NavEntryBuilder.toolsPages(settings: NavigationSettings, navController: NavController) {
    with(settings) {
        entry<Route.Appearance> {
            AppearancePage(
                appBlurMode = appBlurMode,
                onAppBlurModeChange = onAppBlurModeChange,
                onBack = { navController.pop() },
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                useMonet = useMonet,
                onUseMonetChange = onUseMonetChange,
                seedColorHex = seedColorHex,
                onSeedColorChange = onSeedColorChange,
                paletteStyle = paletteStyle,
                onPaletteStyleChange = onPaletteStyleChange,
                pureBlackDarkTheme = pureBlackDarkTheme,
                onPureBlackDarkThemeChange = onPureBlackDarkThemeChange,
                useFloatingBottomBar = useFloatingBottomBar,
                onUseFloatingBottomBarChange = onUseFloatingBottomBarChange,
                floatingBarStyle = floatingBarStyle,
                onFloatingBarStyleChange = onFloatingBarStyleChange,
                predictiveBackStyle = predictiveBackStyle,
                onPredictiveBackStyleChange = onPredictiveBackStyleChange,
                predictiveBackFollowGesture = predictiveBackFollowGesture,
                onPredictiveBackFollowGestureChange = onPredictiveBackFollowGestureChange,
                pageScale = pageScale,
                onPageScaleChange = onPageScaleChange
            )
        }
        entry<Route.ScopePrompts> {
            ScopePromptsPage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.BackupRestore> {
            BackupRestorePage(
                onBack = { navController.pop() },
                onClearAllSettings = onClearAllSettings,
                onSettingsRestored = onSettingsRestored
            )
        }
        entry<Route.About> {
            AboutPage(
                onBack = {
                    navController.pop()
                },
                onViewSourceCode = onViewSourceCode,
                onJoinTelegramGroup = onJoinTelegramGroup,
                onNavigateToCredits = {
                    navController.navigateTo(Route.Credits)
                },
                onNavigateToDebug = {
                    navController.navigateTo(Route.Debug)
                },
                onNavigateToUpdate = {
                    navController.navigateTo(Route.Update)
                },
                updateManager = updateManager
            )
        }
        entry<Route.Update> {
            UpdatePage(
                onBack = { navController.pop() },
                manager = updateManager
            )
        }
        entry<Route.Credits> {
            CreditsPage(
                onBack = {
                    navController.pop()
                }
            )
        }
        entry<Route.HiddenFeatures> {
            HiddenFeaturesPage(
                onBack = {
                    navController.pop()
                },
                onNavigateToDeveloperSettings = {
                    navController.navigateTo(Route.DeveloperSettings)
                }
            )
        }
        entry<Route.AppShortcuts> {
            AppShortcutsPage(
                onBack = {
                    navController.pop()
                },
                onShortcutsChanged = onShortcutsChanged
            )
        }
        entry<Route.Debug> {
            DebugPage(
                onBack = { navController.pop() },
                onNavigateToLogs = { navController.navigateTo(Route.DebugLogs) },
                hotReloading = hotReloading,
                hotReloadTargets = hotReloadTargets,
                hotReloadReport = hotReloadReport,
                onHotReload = onHotReload,
                onRestartAllScopes = onRestartAllScopes
            )
        }
        entry<Route.DeveloperSettings> {
            DeveloperSettingsPage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.DebugLogs> {
            LogsPage(
                onBack = {
                    navController.pop()
                }
            )
        }
        entry<Route.DeviceInfo> {
            DeviceInfoPage(
                onBack = { navController.pop() },
                onBatteryInfo = { navController.navigateTo(Route.BatteryInfo) }
            )
        }
        entry<Route.BatteryInfo> {
            BatteryInfoPage(
                onBack = { navController.pop() }
            )
        }
    }
}
