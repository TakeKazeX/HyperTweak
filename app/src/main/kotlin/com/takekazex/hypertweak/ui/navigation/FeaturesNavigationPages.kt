package com.takekazex.hypertweak.ui.navigation

import androidx.compose.animation.*
import androidx.compose.runtime.*
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.page.AospRestorePage
import com.takekazex.hypertweak.ui.page.SecurityCenterPage
import com.takekazex.hypertweak.ui.page.AospImePage
import com.takekazex.hypertweak.ui.page.GlassTunerPage
import com.takekazex.hypertweak.ui.page.PurificationPage
import com.takekazex.hypertweak.ui.page.GoogleServicesPage
import com.takekazex.hypertweak.ui.page.ExperimentalFeaturesPage
import com.takekazex.hypertweak.ui.page.PowerSavePage
import com.takekazex.hypertweak.ui.page.CameraWatermarkUnlockPage
import com.takekazex.hypertweak.ui.page.ChargingDetailPage
import com.takekazex.hypertweak.ui.page.LockscreenBottomTextPage
import com.takekazex.hypertweak.ui.page.ControlCenterCornerPage
import com.takekazex.hypertweak.ui.page.ControlCenterResizePage
import com.takekazex.hypertweak.ui.page.DownloadManagerPage
import top.yukonga.miuix.kmp.nav.core.*

internal fun NavEntryBuilder.featuresPages(settings: NavigationSettings, navController: NavController) {
    with(settings) {
        entry<Route.AospRestore> {
            AospRestorePage(
                onBack = { navController.pop() },
                onNavigateToAospIme = { navController.navigateTo(Route.AospIme) },
                settingsGlobalInterface = settingsGlobalInterface,
                onSettingsGlobalInterfaceChange = onSettingsGlobalInterfaceChange
            )
        }
        entry<Route.SecurityCenter> {
            SecurityCenterPage(
                onBack = { navController.pop() },
                onNavigateToPowerSave = { navController.navigateTo(Route.PowerSave) }
            )
        }
        entry<Route.AospIme> {
            AospImePage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.Screenshot> {
            com.takekazex.hypertweak.ui.page.ScreenshotPage(onBack = { navController.pop() })
        }
        entry<Route.DownloadManager> {
            DownloadManagerPage(
                onBack = { navController.pop() },
                blockDownloadXlLogDir = blockDownloadXlLogDir,
                onBlockDownloadXlLogDirChange = onBlockDownloadXlLogDirChange,
                alwaysShowFullLink = downloadAlwaysShowFullLink,
                onAlwaysShowFullLinkChange = onDownloadAlwaysShowFullLinkChange,
                hideXl = downloadHideXl,
                onHideXlChange = onDownloadHideXlChange,
                addNewButton = downloadAddNewButton,
                onAddNewButtonChange = onDownloadAddNewButtonChange
            )
        }
        entry<Route.GlassTuner> {
            GlassTunerPage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.CameraWatermark> {
            CameraWatermarkUnlockPage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.Purification> {
            PurificationPage(
                onBack = { navController.pop() },
                disableVideoRingback = disableVideoRingback,
                onDisableVideoRingbackChange = onDisableVideoRingbackChange,
                disableMiTrustRiskMonitoring = disableMiTrustRiskMonitoring,
                onDisableMiTrustRiskMonitoringChange = onDisableMiTrustRiskMonitoringChange,
                disableGuardEnvironmentCheck = disableGuardEnvironmentCheck,
                onDisableGuardEnvironmentCheckChange = onDisableGuardEnvironmentCheckChange,
                blockGuardUploadAppList = blockGuardUploadAppList,
                onBlockGuardUploadAppListChange = onBlockGuardUploadAppListChange,
                blockMiLinkHpplayFiles = blockMiLinkHpplayFiles,
                onBlockMiLinkHpplayFilesChange = onBlockMiLinkHpplayFilesChange,
            )
        }

        entry<Route.GoogleServices> {
            GoogleServicesPage(
                onBack = { navController.pop() },
                showGoogleServicesInSettings = showGoogleServicesInSettings,
                onShowGoogleServicesInSettingsChange = onShowGoogleServicesInSettingsChange,
                removeGms = removeGms,
                onRemoveGmsChange = onRemoveGmsChange,
                quickShareEnabled = quickShareEnabled,
                onQuickShareEnabledChange = onQuickShareEnabledChange,
                fullScreenTranslate = fullScreenTranslate,
                onFullScreenTranslateChange = onFullScreenTranslateChange,
                askAboutScreen = askAboutScreen,
                onAskAboutScreenChange = onAskAboutScreenChange,
                unlockPasskey = unlockPasskey,
                onUnlockPasskeyChange = onUnlockPasskeyChange,
                fcmLiveEnabled = fcmLiveEnabled,
                onFcmLiveEnabledChange = onFcmLiveEnabledChange
            )
        }
        entry<Route.ExperimentalFeatures> {
            ExperimentalFeaturesPage(
                onBack = { navController.pop() },
                onNavigateToGlassTuner = { navController.navigateTo(Route.GlassTuner) },
                onNavigateToControlCenterCorner = { navController.navigateTo(Route.ControlCenterCorner) },
                onNavigateToControlCenterResize = { navController.navigateTo(Route.ControlCenterResize) },
                ccEditEnabled = ccEditEnabled,
                onCcEditEnabledChange = onCcEditEnabledChange
            )
        }
        entry<Route.SystemLauncher> {
            com.takekazex.hypertweak.ui.page.SystemLauncherPage(
                onBack = { navController.pop() },
                onNavigateToDockBackground = { navController.navigateTo(Route.DockBackground) }
            )
        }
        entry<Route.DockBackground> {
            com.takekazex.hypertweak.ui.page.DockBackgroundPage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.PowerSave> {
            PowerSavePage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.ChargingDetail> {
            ChargingDetailPage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.LockscreenBottomText> {
            LockscreenBottomTextPage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.ControlCenterCorner> {
            ControlCenterCornerPage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.ControlCenterResize> {
            ControlCenterResizePage(
                onBack = { navController.pop() }
            )
        }

    }
}
