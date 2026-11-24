package com.takekazex.hypertweak.ui.navigation

import androidx.compose.animation.*
import androidx.compose.runtime.*
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.page.home.MainPagerScreen
import com.takekazex.hypertweak.ui.page.search.SearchPage
import com.takekazex.hypertweak.ui.search.SearchLanding
import top.yukonga.miuix.kmp.nav.core.*

internal fun NavEntryBuilder.mainPages(settings: NavigationSettings, navController: NavController, mainPagerState: com.takekazex.hypertweak.ui.liquid.MainPagerState) {
    with(settings) {
        entry<Route.Main> {
            MainPagerScreen(
                mainPagerState = mainPagerState,
                backdrop = backdrop,
                moduleActive = moduleActive,
                hotReloadAvailable = hotReloadAvailable,
                hotReloading = hotReloading,
                hotReloadTargets = hotReloadTargets,
                hotReloadReport = hotReloadReport,
                pendingRestartScopes = pendingRestartScopes,
                onNavigateToSearch = { navController.navigateTo(Route.Search) },
                onNavigateToSystemLauncher = { navController.navigateTo(Route.SystemLauncher) },
                onNavigateToPhrase = { navController.navigateTo(Route.Phrase) },
                onNavigateToSms = { navController.navigateTo(Route.Sms) },
                onNavigateToScreenRecorder = { navController.navigateTo(Route.ScreenRecorder) },
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
                hideLauncherIcon = hideLauncherIcon,
                onHideLauncherIconChange = onHideLauncherIconChange,
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
                onNavigateToSystemFramework = { navController.navigateTo(Route.SystemFramework) },
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
            )
        }
        entry<Route.Search> {
            SearchPage(
                onBack = { navController.pop() },
                onOpen = { entry ->
                    if (entry.route == Route.Main) {
                        // Main-pager rows: leave search and show the tab that holds the row.
                        navController.navigateTo(Route.Main)
                        if (entry.tab >= 0) mainPagerState.animateToPage(entry.tab)
                    } else {
                        SearchLanding.request(entry.route, entry.tab)
                        navController.navigateTo(entry.route)
                    }
                },
            )
        }
    }
}
