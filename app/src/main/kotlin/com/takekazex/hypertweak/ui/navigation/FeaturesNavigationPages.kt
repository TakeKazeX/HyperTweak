package com.takekazex.hypertweak.ui.navigation

import androidx.compose.animation.*
import androidx.compose.runtime.*
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.page.platform.AospRestorePage
import com.takekazex.hypertweak.ui.page.securitycenter.SecurityCenterPage
import com.takekazex.hypertweak.ui.page.platform.AospImePage
import com.takekazex.hypertweak.ui.page.glass.GlassTunerPage
import com.takekazex.hypertweak.ui.page.platform.PurificationPage
import com.takekazex.hypertweak.ui.page.platform.GoogleServicesPage
import com.takekazex.hypertweak.ui.page.platform.ExperimentalFeaturesPage
import com.takekazex.hypertweak.ui.page.securitycenter.PowerSavePage
import com.takekazex.hypertweak.ui.page.camera.CameraWatermarkUnlockPage
import com.takekazex.hypertweak.ui.page.systemui.lockscreen.ChargingDetailPage
import com.takekazex.hypertweak.ui.page.systemui.lockscreen.LockscreenBottomTextPage
import com.takekazex.hypertweak.ui.page.systemui.controlcenter.ControlCenterCornerPage
import com.takekazex.hypertweak.ui.page.systemui.controlcenter.ControlCenterResizePage
import com.takekazex.hypertweak.ui.page.downloads.DownloadManagerPage
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
                onNavigateToPowerSave = { navController.navigateTo(Route.PowerSave) },
                lbeClipboardToast = lbeClipboardToast,
                onLbeClipboardToastChange = onLbeClipboardToastChange,
            )
        }
        entry<Route.AospIme> {
            AospImePage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.Screenshot> {
            com.takekazex.hypertweak.ui.page.screenshot.ScreenshotPage(onBack = { navController.pop() })
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
            com.takekazex.hypertweak.ui.page.launcher.SystemLauncherPage(
                onBack = { navController.pop() },
                onNavigateToDockBackground = { navController.navigateTo(Route.DockBackground) },
                onNavigateToDesktopIconSize = { navController.navigateTo(Route.DesktopIconSize) }
            )
        }
        entry<Route.DesktopIconSize> {
            com.takekazex.hypertweak.ui.page.launcher.DesktopIconSizePage(onBack = { navController.pop() })
        }
        entry<Route.DockBackground> {
            com.takekazex.hypertweak.ui.page.glass.DockBackgroundPage(
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
