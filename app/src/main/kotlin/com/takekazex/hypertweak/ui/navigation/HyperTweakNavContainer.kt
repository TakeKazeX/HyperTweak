package com.takekazex.hypertweak.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.takekazex.hypertweak.ui.page.Route
import com.takekazex.hypertweak.ui.page.saveKey
import com.takekazex.hypertweak.ui.page.routeFromSaveKey
import com.takekazex.hypertweak.ui.page.MainPagerScreen
import com.takekazex.hypertweak.ui.page.AboutPage
import com.takekazex.hypertweak.ui.page.CreditsPage
import com.takekazex.hypertweak.ui.page.HiddenFeaturesPage
import com.takekazex.hypertweak.ui.page.AppShortcutsPage
import com.takekazex.hypertweak.ui.page.AospRestorePage
import com.takekazex.hypertweak.ui.page.SecurityCenterPage
import com.takekazex.hypertweak.ui.page.AospImePage
import com.takekazex.hypertweak.ui.page.AodIconsPage
import com.takekazex.hypertweak.ui.page.SystemUIPage
import com.takekazex.hypertweak.ui.page.NotificationHeaderPage
import com.takekazex.hypertweak.ui.page.IconTunerPage
import com.takekazex.hypertweak.ui.page.IconOrderPage
import com.takekazex.hypertweak.ui.page.ControlCenterIconRowsPage
import com.takekazex.hypertweak.ui.page.GlassTunerPage
import com.takekazex.hypertweak.ui.page.GoogleServicesPage
import com.takekazex.hypertweak.ui.page.ExperimentalFeaturesPage
import com.takekazex.hypertweak.ui.page.PowerSavePage
import com.takekazex.hypertweak.ui.page.CameraWatermarkUnlockPage
import com.takekazex.hypertweak.ui.page.ChargingDetailPage
import com.takekazex.hypertweak.ui.page.LockscreenBottomTextPage
import com.takekazex.hypertweak.ui.page.ControlCenterCornerPage
import com.takekazex.hypertweak.ui.page.ControlCenterResizePage
import com.takekazex.hypertweak.ui.page.DebugPage
import com.takekazex.hypertweak.ui.page.DeveloperSettingsPage
import com.takekazex.hypertweak.ui.page.BatteryInfoPage
import com.takekazex.hypertweak.ui.page.DownloadManagerPage
import com.takekazex.hypertweak.ui.page.LogsPage
import com.takekazex.hypertweak.ui.page.AppearancePage
import com.takekazex.hypertweak.ui.page.ScopePromptsPage
import com.takekazex.hypertweak.ui.page.BackupRestorePage
import com.takekazex.hypertweak.ui.page.UpdatePage
import com.takekazex.hypertweak.hook.HotReloadReport
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.update.UpdateManager
import top.yukonga.miuix.kmp.nav.core.*
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import com.takekazex.hypertweak.ui.effect.scaleBackTransition
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.blur.LayerBackdrop

@Composable
fun HyperTweakNavContainer(
    appBlurMode: Int,
    onAppBlurModeChange: (Int) -> Unit,
    // Theme & Navigation States
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
    useMonet: Boolean,
    onUseMonetChange: (Boolean) -> Unit,
    seedColorHex: Int,
    onSeedColorChange: (Int) -> Unit,
    paletteStyle: Int,
    onPaletteStyleChange: (Int) -> Unit,
    pureBlackDarkTheme: Boolean,
    onPureBlackDarkThemeChange: (Boolean) -> Unit,
    useFloatingBottomBar: Boolean,
    onUseFloatingBottomBarChange: (Boolean) -> Unit,
    floatingBarStyle: Int,
    onFloatingBarStyleChange: (Int) -> Unit,
    predictiveBackStyle: Int,
    onPredictiveBackStyleChange: (Int) -> Unit,
    predictiveBackFollowGesture: Boolean,
    onPredictiveBackFollowGestureChange: (Boolean) -> Unit,
    allowLandscape: Boolean,
    onAllowLandscapeChange: (Boolean) -> Unit,

    // Module Settings States
    moduleActive: Boolean,
    hotReloadAvailable: Boolean,
    hotReloading: Boolean,
    hotReloadTargets: List<String>,
    hotReloadReport: HotReloadReport?,
    pendingRestartScopes: RestartScopeSelection,
    aodFullscreen: Boolean,
    onAodFullscreenChange: (Boolean) -> Unit,
    blockDownloadXlLogDir: Boolean,
    onBlockDownloadXlLogDirChange: (Boolean) -> Unit,
    downloadAlwaysShowFullLink: Boolean,
    onDownloadAlwaysShowFullLinkChange: (Boolean) -> Unit,
    downloadHideXl: Boolean,
    onDownloadHideXlChange: (Boolean) -> Unit,
    downloadAddNewButton: Boolean,
    onDownloadAddNewButtonChange: (Boolean) -> Unit,
    removeGms: Boolean,
    onRemoveGmsChange: (Boolean) -> Unit,
    quickShareEnabled: Boolean,
    onQuickShareEnabledChange: (Boolean) -> Unit,
    fullScreenTranslate: Boolean,
    onFullScreenTranslateChange: (Boolean) -> Unit,
    askAboutScreen: Boolean,
    onAskAboutScreenChange: (Boolean) -> Unit,
    hideFingerprintAod: Boolean,
    onHideFingerprintAodChange: (Boolean) -> Unit,
    hideFingerprintLockscreen: Boolean,
    onHideFingerprintLockscreenChange: (Boolean) -> Unit,
    hideFingerprintAppAuth: Boolean,
    onHideFingerprintAppAuthChange: (Boolean) -> Unit,
    hideLockscreenStatusBar: Boolean,
    onHideLockscreenStatusBarChange: (Boolean) -> Unit,
    notificationHeaderClockSeconds: Boolean,
    onNotificationHeaderClockSecondsChange: (Boolean) -> Unit,
    notificationMonetTextColor: Boolean,
    onNotificationMonetTextColorChange: (Boolean) -> Unit,
    notificationFontWeight: Boolean,
    onNotificationFontWeightChange: (Boolean) -> Unit,
    notificationAbsoluteTime: Boolean,
    onNotificationAbsoluteTimeChange: (Boolean) -> Unit,
    lockscreenFingerprintAvoid: Int,
    onLockscreenFingerprintAvoidChange: (Int) -> Unit,
    sliderShowPercentage: Boolean,
    onSliderShowPercentageChange: (Boolean) -> Unit,
    sliderSamePercentageStyle: Boolean,
    onSliderSamePercentageChange: (Boolean) -> Unit,
    ccEditEnabled: Boolean,
    onCcEditEnabledChange: (Boolean) -> Unit,
    paModelSpoofEnabled: Boolean,
    onPaModelSpoofEnabledChange: (Boolean) -> Unit,
    mediaCardHideAppIcon: Boolean,
    onMediaCardHideAppIconChange: (Boolean) -> Unit,
    mediaCardHideDeviceSwitch: Boolean,
    onMediaCardHideDeviceSwitchChange: (Boolean) -> Unit,
    lockscreenAllNotifications: Boolean,
    onLockscreenAllNotificationsChange: (Boolean) -> Unit,
    lockscreenKeepNotifications: Boolean,
    onLockscreenKeepNotificationsChange: (Boolean) -> Unit,
    showInSettings: Boolean,
    onShowInSettingsChange: (Boolean) -> Unit,
    showGoogleServicesInSettings: Boolean,
    onShowGoogleServicesInSettingsChange: (Boolean) -> Unit,
    settingsGlobalInterface: Boolean,
    onSettingsGlobalInterfaceChange: (Boolean) -> Unit,
    disableVideoRingback: Boolean,
    onDisableVideoRingbackChange: (Boolean) -> Unit,
    hideLauncherIcon: Boolean,
    onHideLauncherIconChange: (Boolean) -> Unit,
    immediateMonetRefresh: Boolean,
    onImmediateMonetRefreshChange: (Boolean) -> Unit,
    hideGestureBar: Boolean,
    onHideGestureBarChange: (Boolean) -> Unit,
    gestureBarRaiseLayout: Boolean,
    onGestureBarRaiseLayoutChange: (Boolean) -> Unit,
    powerButtonAction: Int,
    onPowerButtonActionChange: (Int) -> Unit,
    contextualSearchLongPress: Boolean,
    onContextualSearchLongPressChange: (Boolean) -> Unit,
    unlockPasskey: Boolean,
    onUnlockPasskeyChange: (Boolean) -> Unit,
    unlockThirdPartyDarkMode: Boolean,
    onUnlockThirdPartyDarkModeChange: (Boolean) -> Unit,
    disableSpatialAudio: Boolean,
    onDisableSpatialAudioChange: (Boolean) -> Unit,
    forceAdaptiveAnc: Boolean,
    onForceAdaptiveAncChange: (Boolean) -> Unit,
    fcmLiveEnabled: Boolean,
    onFcmLiveEnabledChange: (Boolean) -> Unit,
    lbeClipboardToast: Boolean,
    onLbeClipboardToastChange: (Boolean) -> Unit,
    disableMiTrustRiskMonitoring: Boolean,
    onDisableMiTrustRiskMonitoringChange: (Boolean) -> Unit,
    disableGuardEnvironmentCheck: Boolean,
    onDisableGuardEnvironmentCheckChange: (Boolean) -> Unit,
    blockGuardUploadAppList: Boolean,
    onBlockGuardUploadAppListChange: (Boolean) -> Unit,
    blockMiLinkHpplayFiles: Boolean,
    onBlockMiLinkHpplayFilesChange: (Boolean) -> Unit,
    allowThirdPartyTheme: Boolean,
    onAllowThirdPartyThemeChange: (Boolean) -> Unit,
    focusNotificationUnlockWhitelist: Boolean,
    onFocusNotificationUnlockWhitelistChange: (Boolean) -> Unit,
    mediaSuperIslandUnlockWhitelist: Boolean,
    onMediaSuperIslandUnlockWhitelistChange: (Boolean) -> Unit,
    xmsfUnlockFocusAuth: Boolean,
    onXmsfUnlockFocusAuthChange: (Boolean) -> Unit,

    // Backdrop
    backdrop: LayerBackdrop,

    // Scaling
    pageScale: Float,
    onPageScaleChange: (Float) -> Unit,

    // Actions
    onViewSourceCode: () -> Unit,
    onJoinTelegramGroup: () -> Unit,
    onClearAllSettings: (restartAllScopes: Boolean, restartHyperTweak: Boolean) -> Unit,
    onSettingsRestored: () -> Unit,
    onHotReload: () -> Unit,
    onRestartAllScopes: () -> Unit,
    onRestartScope: (RestartScopeSelection) -> Unit,
    onShortcutsChanged: () -> Unit,
    appLanguage: Int,
    onAppLanguageChange: (Int) -> Unit,
    updateManager: UpdateManager,
    snackbarHostState: SnackbarHostState
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val mainPagerState = com.takekazex.hypertweak.ui.liquid.rememberMainPagerState(pagerState)
    LaunchedEffect(mainPagerState.pagerState.currentPage) {
        mainPagerState.syncPage()
    }
    // Persist the back stack across process death: routes are data objects, so serialize them to
    // their stable keys and rebuild the observable list on restore. Falls back to [Route.Main] if
    // nothing (or only unknown keys) was saved, keeping the same initial entry as a fresh launch.
    val backStack = rememberSaveable(
        saver = listSaver(
            save = { stack -> stack.map { (it as Route).saveKey } },
            restore = { keys ->
                keys.mapNotNull { routeFromSaveKey(it) as NavKey? }
                    .ifEmpty { listOf(Route.Main) }
                    .toMutableStateList()
            }
        )
    ) { mutableStateListOf<NavKey>(Route.Main) }

    val navController = remember(backStack) { NavController(backStack) }

    val isPagerBackHandlerEnabled by remember(backStack, mainPagerState.selectedPage) {
        derivedStateOf {
            backStack.lastOrNull() is Route.Main && backStack.size == 1 && mainPagerState.selectedPage != 0
        }
    }

    BackHandler(enabled = isPagerBackHandlerEnabled) {
        mainPagerState.animateToPage(0)
    }

    val entryProvider: NavEntryBuilder.() -> Unit = {
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
                onNavigateToSystemUi = {
                    navController.push(Route.SystemUi)
                },
                onNavigateToDownloadManager = {
                    navController.push(Route.DownloadManager)
                },
                onNavigateToSecurityCenter = {
                    navController.push(Route.SecurityCenter)
                },
                showInSettings = showInSettings,
                onShowInSettingsChange = onShowInSettingsChange,
                disableVideoRingback = disableVideoRingback,
                onDisableVideoRingbackChange = onDisableVideoRingbackChange,
                hideLauncherIcon = hideLauncherIcon,
                onHideLauncherIconChange = onHideLauncherIconChange,
                 unlockThirdPartyDarkMode = unlockThirdPartyDarkMode,
                 onUnlockThirdPartyDarkModeChange = onUnlockThirdPartyDarkModeChange,
                disableSpatialAudio = disableSpatialAudio,
                onDisableSpatialAudioChange = onDisableSpatialAudioChange,
                forceAdaptiveAnc = forceAdaptiveAnc,
                onForceAdaptiveAncChange = onForceAdaptiveAncChange,
                disableMiTrustRiskMonitoring = disableMiTrustRiskMonitoring,
                onDisableMiTrustRiskMonitoringChange = onDisableMiTrustRiskMonitoringChange,
                disableGuardEnvironmentCheck = disableGuardEnvironmentCheck,
                onDisableGuardEnvironmentCheckChange = onDisableGuardEnvironmentCheckChange,
                blockGuardUploadAppList = blockGuardUploadAppList,
                onBlockGuardUploadAppListChange = onBlockGuardUploadAppListChange,
                blockMiLinkHpplayFiles = blockMiLinkHpplayFiles,
                onBlockMiLinkHpplayFilesChange = onBlockMiLinkHpplayFilesChange,
                allowThirdPartyTheme = allowThirdPartyTheme,
                onAllowThirdPartyThemeChange = onAllowThirdPartyThemeChange,
                focusNotificationUnlockWhitelist = focusNotificationUnlockWhitelist,
                onFocusNotificationUnlockWhitelistChange = onFocusNotificationUnlockWhitelistChange,
                mediaSuperIslandUnlockWhitelist = mediaSuperIslandUnlockWhitelist,
                onMediaSuperIslandUnlockWhitelistChange = onMediaSuperIslandUnlockWhitelistChange,
                xmsfUnlockFocusAuth = xmsfUnlockFocusAuth,
                onXmsfUnlockFocusAuthChange = onXmsfUnlockFocusAuthChange,
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
                    navController.push(Route.About)
                },
                onNavigateToAppearance = {
                    navController.push(Route.Appearance)
                },
                onNavigateToScopePrompts = {
                    navController.push(Route.ScopePrompts)
                },
                onNavigateToBackupRestore = {
                    navController.push(Route.BackupRestore)
                },
                onNavigateToHiddenFeatures = {
                    navController.push(Route.HiddenFeatures)
                },
                onNavigateToAppShortcuts = {
                    navController.push(Route.AppShortcuts)
                },
                onNavigateToAospRestore = {
                    navController.push(Route.AospRestore)
                },
                onNavigateToGoogleServices = {
                    navController.push(Route.GoogleServices)
                },
                onNavigateToIconTuner = {
                    navController.push(Route.IconTuner)
                },
                onNavigateToGlassTuner = {
                    navController.push(Route.GlassTuner)
                },
                onNavigateToCameraWatermark = {
                    navController.push(Route.CameraWatermark)
                },
                onNavigateToExperimentalFeatures = {
                    navController.push(Route.ExperimentalFeatures)
                },
                onNavigateToControlCenterCorner = {
                    navController.push(Route.ControlCenterCorner)
                },
                onNavigateToControlCenterResize = {
                    navController.push(Route.ControlCenterResize)
                },
                onNavigateToBatteryInfo = {
                    navController.push(Route.BatteryInfo)
                },
                onHotReload = onHotReload,
                onRestartAllScopes = onRestartAllScopes,
                onRestartScope = onRestartScope,
                snackbarHostState = snackbarHostState,
                appLanguage = appLanguage,
                onAppLanguageChange = onAppLanguageChange
            )
        }
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
                    navController.push(Route.Credits)
                },
                onNavigateToDebug = {
                    navController.push(Route.Debug)
                },
                onNavigateToUpdate = {
                    navController.push(Route.Update)
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
                    navController.push(Route.DeveloperSettings)
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
        entry<Route.AospRestore> {
            AospRestorePage(
                onBack = { navController.pop() },
                onNavigateToAospIme = { navController.push(Route.AospIme) },
                settingsGlobalInterface = settingsGlobalInterface,
                onSettingsGlobalInterfaceChange = onSettingsGlobalInterfaceChange
            )
        }
        entry<Route.SecurityCenter> {
            SecurityCenterPage(
                onBack = { navController.pop() },
                onNavigateToPowerSave = { navController.push(Route.PowerSave) }
            )
        }
        entry<Route.AospIme> {
            AospImePage(
                onBack = { navController.pop() }
            )
        }
        entry<Route.IconTuner> {
            IconTunerPage(
                onBack = { navController.pop() },
                onNavigateToIconOrder = { navController.push(Route.IconOrder) },
                onNavigateToIconRows = { navController.push(Route.ControlCenterIconRows) }
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
                notificationHeaderClockSeconds = notificationHeaderClockSeconds,
                onNotificationHeaderClockSecondsChange = onNotificationHeaderClockSecondsChange,
                notificationMonetTextColor = notificationMonetTextColor,
                onNotificationMonetTextColorChange = onNotificationMonetTextColorChange,
                notificationFontWeight = notificationFontWeight,
                onNotificationFontWeightChange = onNotificationFontWeightChange,
                notificationAbsoluteTime = notificationAbsoluteTime,
                onNotificationAbsoluteTimeChange = onNotificationAbsoluteTimeChange,
                lockscreenFingerprintAvoid = lockscreenFingerprintAvoid,
                onLockscreenFingerprintAvoidChange = onLockscreenFingerprintAvoidChange,
                onNavigateToChargingDetail = {
                    navController.push(Route.ChargingDetail)
                },
                onNavigateToLockscreenBottomText = {
                    navController.push(Route.LockscreenBottomText)
                },
                lockscreenAllNotifications = lockscreenAllNotifications,
                onLockscreenAllNotificationsChange = onLockscreenAllNotificationsChange,
                lockscreenKeepNotifications = lockscreenKeepNotifications,
                onLockscreenKeepNotificationsChange = onLockscreenKeepNotificationsChange,
                mediaCardHideAppIcon = mediaCardHideAppIcon,
                onMediaCardHideAppIconChange = onMediaCardHideAppIconChange,
                mediaCardHideDeviceSwitch = mediaCardHideDeviceSwitch,
                onMediaCardHideDeviceSwitchChange = onMediaCardHideDeviceSwitchChange,
                sliderShowPercentage = sliderShowPercentage,
                onSliderShowPercentageChange = onSliderShowPercentageChange,
                sliderSamePercentageStyle = sliderSamePercentageStyle,
                onSliderSamePercentageChange = onSliderSamePercentageChange,
                hideGestureBar = hideGestureBar,
                onHideGestureBarChange = onHideGestureBarChange,
                gestureBarRaiseLayout = gestureBarRaiseLayout,
                onGestureBarRaiseLayoutChange = onGestureBarRaiseLayoutChange,
                powerButtonAction = powerButtonAction,
                onPowerButtonActionChange = onPowerButtonActionChange,
                contextualSearchLongPress = contextualSearchLongPress,
                onContextualSearchLongPressChange = onContextualSearchLongPressChange,
                onNavigateToNotificationHeader = {
                    navController.push(Route.NotificationHeader)
                },
                onNavigateToIconTuner = {
                    navController.push(Route.IconTuner)
                },
                onNavigateToAodIcons = { navController.push(Route.AodIcons) }
            )
        }
        entry<Route.AodIcons> {
            AodIconsPage(onBack = { navController.pop() })
        }
        entry<Route.NotificationHeader> {
            NotificationHeaderPage(
                onBack = { navController.pop() }
            )
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
                onNavigateToGlassTuner = { navController.push(Route.GlassTuner) },
                onNavigateToDockBackground = { navController.push(Route.DockBackground) },
                onNavigateToControlCenterCorner = { navController.push(Route.ControlCenterCorner) },
                onNavigateToControlCenterResize = { navController.push(Route.ControlCenterResize) },
                ccEditEnabled = ccEditEnabled,
                onCcEditEnabledChange = onCcEditEnabledChange
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
        entry<Route.Debug> {
            DebugPage(
                onBack = { navController.pop() },
                onNavigateToLogs = { navController.push(Route.DebugLogs) },
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
        entry<Route.BatteryInfo> {
            BatteryInfoPage(
                onBack = { navController.pop() }
            )
        }
    }

    val scaleTransition = remember(predictiveBackFollowGesture) {
        scaleBackTransition(predictiveBackFollowGesture)
    }
    NavDisplay(
        backStack = backStack,
        onBack = { navController.pop() },
        transition = when (predictiveBackStyle) {
            0 -> NavTransitions.None
            2 -> scaleTransition
            else -> NavTransitions.MiuixDefault
        },
        effects = if (predictiveBackStyle == 0) NavDisplayEffects.None else NavDisplayEffects(
            cornerClipRadius = rememberNavSystemCornerRadius(),
            cornerClipMode = if (predictiveBackStyle == 2) NavCornerClipMode.All else NavCornerClipMode.Leading,
        ),
        content = entryProvider,
    )
}
