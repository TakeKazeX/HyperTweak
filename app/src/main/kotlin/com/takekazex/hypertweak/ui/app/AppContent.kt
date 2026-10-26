package com.takekazex.hypertweak.ui.app

import com.takekazex.hypertweak.settings.contract.AppSettingsStore
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Density
import androidx.core.net.toUri
import androidx.activity.ComponentActivity
import com.takekazex.hypertweak.util.update.UpdateManager
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.AodSettingsKeys
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.ui.effect.AppBlurMode
import com.takekazex.hypertweak.ui.effect.LocalAppBlurMode
import com.takekazex.hypertweak.ui.navigation.HyperTweakNavContainer
import com.takekazex.hypertweak.ui.navigation.NavigationSettings
import com.takekazex.hypertweak.ui.components.dialog.LocalRestartScopeHandled
import com.takekazex.hypertweak.ui.components.dialog.LocalRestartScopeRequest
import com.takekazex.hypertweak.ui.page.update.UpdateAvailablePrompt
import com.takekazex.hypertweak.ui.theme.MiuixSpec2025Adapter
import com.takekazex.hypertweak.ui.theme.isEffectivelyDark
import com.takekazex.hypertweak.ui.theme.rememberDeviceAccentColor
import com.takekazex.hypertweak.util.platform.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AppContent(
    activity: ComponentActivity,
    updateManager: UpdateManager,
    isModuleActive: () -> Boolean,
    store: AppSettingsStore,
    services: com.takekazex.hypertweak.app.AppServicePorts
) {
    val settings = remember { AppSettingsState(store) }
    val restarts = remember { AppRestartTracker(activity, settings) }
    with(settings) {
        with(restarts) {

            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val localizedContext = remember(context, appLanguage, configuration) {
                LocaleHelper.getLocalizedContext(context, appLanguage)
            }
            val snackbarHostState = remember { SnackbarHostState() }

            ReconcileUpdates(activity, updateManager, snackbarHostState, localizedContext)

            val serviceConnected by services.module.connection.collectAsState()
            val staleTargets by services.module.staleProcessNames.collectAsState()
            val hotReloading by services.module.hotReloadingFlow.collectAsState()
            val hotReloadReport by services.module.hotReloadReportFlow.collectAsState()

            val coroutineScope = rememberCoroutineScope()

            LaunchedEffect(allowLandscape) {
                activity.requestedOrientation = if (allowLandscape) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }

            val moduleActive = rememberModuleActivation(settings, restarts, serviceConnected, isModuleActive)
            /**
             * Quick Share on CN GMS works through a phenotype override that only GMS's own
             * process can write. GMS is a declared required scope, so the toggle only flips the
             * preference and marks the tweak dirty: the standard Home restart button then
             * restarts Google Play services, and the hooker writes the `sharing_supports_latchsky`
             * row (or removes it) at package-ready.
             */
            fun handleQuickShareChange(checked: Boolean) {
                quickShareEnabled = checked
                markTweaked(PlatformSettingsKeys.KEY_QUICK_SHARE_ENABLED, checked)
                settings.storage.putBoolean(PlatformSettingsKeys.KEY_QUICK_SHARE_ENABLED, checked)
            }

            /**
             * Removing the Super Island notification whitelist installs SystemUI hooks (SystemUI is a
             * declared required scope), so the toggle flips the preference and marks the tweak
             * dirty: the standard Home restart button then restarts SystemUI. The hooker reads the
             * per-app `<pkg>_focus` preference live, so a user's explicit shade-menu off for a
             * given app is always respected and disabling applies without another restart once
             * the hooks are installed.
             */
            fun handleFocusNotificationUnlockWhitelistChange(checked: Boolean) {
                focusNotificationUnlockWhitelist = checked
                markTweaked(PlatformSettingsKeys.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, checked)
                settings.storage.putBoolean(PlatformSettingsKeys.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, checked)
            }

            /**
             * Removing the media Super Island dropdown whitelist uses its own SystemUI hook and
             * `<pkg>_focusmedia` per-app state, so it can be enabled without changing the normal
             * Super Island notification gate.
             */
            fun handleMediaSuperIslandUnlockWhitelistChange(checked: Boolean) {
                mediaSuperIslandUnlockWhitelist = checked
                markTweaked(PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST, checked)
                settings.storage.putBoolean(PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST, checked)
            }

            /** The toggle only changes the preference; scope membership is managed separately. */
            fun handleXmsfUnlockFocusAuthChange(checked: Boolean) {
                xmsfUnlockFocusAuth = checked
                markTweaked(PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH, checked)
                settings.storage.putBoolean(PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH, checked)
            }

            /** The toggle only changes the preference; scope membership is managed separately. */
            fun handleFullScreenTranslateChange(checked: Boolean) {
                fullScreenTranslate = checked
                markTweaked(PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE, checked)
                settings.storage.putBoolean(PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE, checked)
            }

            /** The toggle only changes the preference; scope membership is managed separately. */
            fun handleAskAboutScreenChange(checked: Boolean) {
                askAboutScreen = checked
                markTweaked(PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN, checked)
                settings.storage.putBoolean(PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN, checked)
            }

            /** Model spoof is read on every assistant request, so no process restart is needed. */
            fun handlePaModelSpoofChange(checked: Boolean) {
                paModelSpoofEnabled = checked
                settings.storage.putBoolean(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF, checked)
            }

            val isDark = isSystemInDarkTheme()
            val deviceAccent = rememberDeviceAccentColor()
            val resolvedSeedColorHex = if (seedColorHex == 0) deviceAccent else seedColorHex

            val pureBlackActive = pureBlackDarkTheme && isEffectivelyDark(themeMode, isDark)
            val controller = MiuixSpec2025Adapter.rememberThemeController(
                themeMode, useMonet, resolvedSeedColorHex, paletteStyle, pureBlackActive
            )

            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, pageScale) {
                Density(systemDensity.density * pageScale, systemDensity.fontScale)
            }

            CompositionLocalProvider(
                LocalAppSettingsStore provides store,
                LocalAppServices provides services,
                // A localized ConfigurationContext is not an Activity. Keep Activity-owned
                // launchers and back dispatch available to navigation entries and windows.
                LocalActivity provides activity,
                LocalActivityResultRegistryOwner provides activity,
                LocalOnBackPressedDispatcherOwner provides activity,
                LocalAppBlurMode provides AppBlurMode.entries[appBlurMode],
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
                LocalResources provides localizedContext.resources,
                LocalDensity provides density,
                LocalRestartScopeRequest provides ::requestRestartScopes,
                LocalRestartScopeHandled provides ::clearRestartedScopes
            ) {
                MiuixTheme(controller = controller) {
                    val surfaceColor = MiuixTheme.colorScheme.surface
                    val backdrop = rememberLayerBackdrop {
                        drawRect(surfaceColor)
                        drawContent()
                    }

                    HyperTweakNavContainer(settings = NavigationSettings(
                        appBlurMode = appBlurMode,
                        onAppBlurModeChange = { mode ->
                            appBlurMode = mode.coerceIn(0, 2)
                            settings.storage.putInt(AppSettingsKeys.KEY_APP_BLUR_MODE, appBlurMode)
                        },
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            settings.storage.putInt(AppSettingsKeys.KEY_THEME_MODE, mode)
                        },
                        useMonet = useMonet,
                        onUseMonetChange = { monet ->
                            useMonet = monet
                            settings.storage.putBoolean(AppSettingsKeys.KEY_USE_MONET, monet)
                        },
                        seedColorHex = seedColorHex,
                        onSeedColorChange = { color ->
                            seedColorHex = color
                            settings.storage.putInt(AppSettingsKeys.KEY_SEED_COLOR, color)
                        },
                        paletteStyle = paletteStyle,
                        onPaletteStyleChange = { style ->
                            paletteStyle = style
                            settings.storage.putInt(AppSettingsKeys.KEY_THEME_PALETTE_STYLE, style)
                        },
                        pureBlackDarkTheme = pureBlackDarkTheme,
                        onPureBlackDarkThemeChange = { enabled ->
                            pureBlackDarkTheme = enabled
                            settings.storage.putBoolean(AppSettingsKeys.KEY_PURE_BLACK_DARK_THEME, enabled)
                        },
                        useFloatingBottomBar = useFloatingBottomBar,
                        onUseFloatingBottomBarChange = { floating ->
                            useFloatingBottomBar = floating
                            settings.storage.putBoolean(PlatformSettingsKeys.KEY_USE_FLOATING_BOTTOM_BAR, floating)
                        },
                        floatingBarStyle = floatingBarStyle,
                        onFloatingBarStyleChange = { style ->
                            floatingBarStyle = style
                            settings.storage.putInt(PlatformSettingsKeys.KEY_FLOATING_BAR_STYLE, style)
                        },
                        predictiveBackStyle = predictiveBackStyle,
                        onPredictiveBackStyleChange = { style ->
                            predictiveBackStyle = style
                            settings.storage.putInt(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_STYLE, style)
                        },
                        predictiveBackFollowGesture = predictiveBackFollowGesture,
                        onPredictiveBackFollowGestureChange = { follow ->
                            predictiveBackFollowGesture = follow
                            settings.storage.putBoolean(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, follow)
                        },
                        allowLandscape = allowLandscape,
                        onAllowLandscapeChange = { allowed ->
                            allowLandscape = allowed
                            settings.storage.putBoolean(PlatformSettingsKeys.KEY_ALLOW_LANDSCAPE, allowed)
                        },
                        moduleActive = moduleActive,
                        hotReloadAvailable = staleTargets.isNotEmpty(),
                        hotReloading = hotReloading,
                        hotReloadTargets = staleTargets,
                        hotReloadReport = hotReloadReport,
                        pendingRestartScopes = pendingRestartScopes,
                        aodFullscreen = aodFullscreen,
                        onAodFullscreenChange = { checked ->
                            aodFullscreen = checked
                            settings.storage.putBoolean(AodSettingsKeys.KEY_AOD_FULLSCREEN, checked)
                        },
                        blockDownloadXlLogDir = blockDownloadXlLogDir,
                        onBlockDownloadXlLogDirChange = { checked ->
                            markTweaked(PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, checked)
                            blockDownloadXlLogDir = checked
                            settings.storage.putBoolean(PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, checked)
                        },
                        downloadAlwaysShowFullLink = downloadAlwaysShowFullLink,
                        onDownloadAlwaysShowFullLinkChange = { checked ->
                            markTweaked(PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK, checked)
                            downloadAlwaysShowFullLink = checked
                            settings.storage.putBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK, checked)
                        },
                        downloadHideXl = downloadHideXl,
                        onDownloadHideXlChange = { checked ->
                            markTweaked(PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL, checked)
                            downloadHideXl = checked
                            settings.storage.putBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL, checked)
                        },
                        downloadAddNewButton = downloadAddNewButton,
                        onDownloadAddNewButtonChange = { checked ->
                            markTweaked(PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON, checked)
                            downloadAddNewButton = checked
                            settings.storage.putBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON, checked)
                        },
                        // Google services and the experimental switches now live on their own
                        // second-level pages; the nav container consumes their state directly.
                        removeGms = removeGms,
                        onRemoveGmsChange = { checked ->
                            removeGms = checked
                            settings.storage.putBoolean(PlatformSettingsKeys.KEY_REMOVE_GMS_RESTRICTION, checked)
                        },
                        quickShareEnabled = quickShareEnabled,
                        onQuickShareEnabledChange = { checked -> handleQuickShareChange(checked) },
                        fullScreenTranslate = fullScreenTranslate,
                        onFullScreenTranslateChange = { checked ->
                            handleFullScreenTranslateChange(checked)
                        },
                        askAboutScreen = askAboutScreen,
                        onAskAboutScreenChange = { checked ->
                            handleAskAboutScreenChange(checked)
                        },
                        showGoogleServicesInSettings = showGoogleServicesInSettings,
                        onShowGoogleServicesInSettingsChange = { checked ->
                            markTweaked(PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS, checked)
                            showGoogleServicesInSettings = checked
                            settings.storage.putBoolean(
                                PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS,
                                checked
                        )
                    },
                    settingsGlobalInterface = settingsGlobalInterface,
                    onSettingsGlobalInterfaceChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE, checked)
                        settingsGlobalInterface = checked
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE, checked)
                    },
                    unlockPasskey = unlockPasskey,
                    onUnlockPasskeyChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_UNLOCK_PASSKEY, checked)
                        unlockPasskey = checked
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_UNLOCK_PASSKEY, checked)
                    },
                    fcmLiveEnabled = fcmLiveEnabled,
                    onFcmLiveEnabledChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED, checked)
                        fcmLiveEnabled = checked
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED, checked)
                    },
                    lbeClipboardToast = lbeClipboardToast,
                    onLbeClipboardToastChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_LBE_CLIPBOARD_TOAST, checked)
                        lbeClipboardToast = checked
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_LBE_CLIPBOARD_TOAST, checked)
                    },
                    ccEditEnabled = ccEditEnabled,
                    onCcEditEnabledChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED, checked)
                        ccEditEnabled = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED, checked)
                    },
                    hideFingerprintAod = hideFingerprintAod,
                    onHideFingerprintAodChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD, checked)
                        hideFingerprintAod = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD, checked)
                    },
                    hideFingerprintLockscreen = hideFingerprintLockscreen,
                    onHideFingerprintLockscreenChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN, checked)
                        hideFingerprintLockscreen = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN, checked)
                    },
                    hideFingerprintAppAuth = hideFingerprintAppAuth,
                    onHideFingerprintAppAuthChange = { checked ->
                        markTweaked(
                            SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH,
                            checked,
                            defaultValue = settings.storage.getBoolean(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT, false)
                        )
                        hideFingerprintAppAuth = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH, checked)
                    },
                    hideLockscreenStatusBar = hideLockscreenStatusBar,
                    onHideLockscreenStatusBarChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, checked)
                        hideLockscreenStatusBar = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, checked)
                    },
                    notificationHeaderClockSeconds = notificationHeaderClockSeconds,
                    onNotificationHeaderClockSecondsChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS, checked)
                        notificationHeaderClockSeconds = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS, checked)
                    },
                    notificationMonetTextColor = notificationMonetTextColor,
                    onNotificationMonetTextColorChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR, checked)
                        notificationMonetTextColor = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR, checked)
                    },
                    notificationAbsoluteTime = notificationAbsoluteTime,
                    onNotificationAbsoluteTimeChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME, checked)
                        notificationAbsoluteTime = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME, checked)
                    },
                    notificationFontWeight = notificationFontWeight,
                    onNotificationFontWeightChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT, checked)
                        notificationFontWeight = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT, checked)
                    },
                    lockscreenFingerprintAvoid = lockscreenFingerprintAvoid,
                    onLockscreenFingerprintAvoidChange = { mode ->
                        markTweakedInt(SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID, mode)
                        lockscreenFingerprintAvoid = mode
                        settings.storage.putInt(SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID, mode)
                    },
                    hideGestureBar = hideGestureBar,
                    onHideGestureBarChange = { checked ->
                        hideGestureBar = checked
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR, checked)
                    },
                    gestureBarRaiseLayout = gestureBarRaiseLayout,
                    onGestureBarRaiseLayoutChange = { checked ->
                        gestureBarRaiseLayout = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT, checked)
                    },
                    hideGestureBarRecents = hideGestureBarRecents,
                    onHideGestureBarRecentsChange = { checked ->
                        hideGestureBarRecents = checked
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_RECENTS, checked)
                    },
                    hideGestureBarLauncher = hideGestureBarLauncher,
                    onHideGestureBarLauncherChange = { checked ->
                        // SystemUI re-reads the top task from its own task-stack callbacks, so
                        // the switch applies on the next task move without a restart.
                        hideGestureBarLauncher = checked
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_LAUNCHER, checked)
                    },
                    gestureBarAttachTransition = gestureBarAttachTransition,
                    onGestureBarAttachTransitionChange = { checked ->
                        // Shared settings reach system_server; its native transition gate reads
                        // the accepted value on the next swipe, including after APK replacement.
                        gestureBarAttachTransition = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION, checked)
                    },
                    contextualSearchHaptics = contextualSearchHaptics,
                    onContextualSearchHapticsChange = { enabled ->
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_HAPTICS, enabled)
                        contextualSearchHaptics = enabled
                    },
                    contextualSearchLongPress = contextualSearchLongPress,
                    onContextualSearchLongPressChange = { enabled ->
                        contextualSearchLongPress = enabled
                        settings.storage.putBoolean(
                            PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_LONG_PRESS,
                            enabled
                        )
                    },
                    powerButtonAction = powerButtonAction,
                    onPowerButtonActionChange = { action ->
                        // System-server hooks: no restart scope exists, so no markTweaked; the
                        // hookers read the action live at dispatch time, so switching actions (or
                        // off) applies immediately once the hooks are installed. Turning the
                        // feature on from disabled still needs a reboot for the hooks to install.
                        powerButtonAction = action
                        settings.storage.powerButton.setPowerButtonAction(action)
                    },
                    sliderShowPercentage = sliderShowPercentage,
                    onSliderShowPercentageChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE, checked)
                        sliderShowPercentage = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE, checked)
                    },
                    sliderSamePercentageStyle = sliderSamePercentageStyle,
                    onSliderSamePercentageChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE, checked)
                        sliderSamePercentageStyle = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE, checked)
                    },
                    paModelSpoofEnabled = paModelSpoofEnabled,
                    mediaCardHideAppIcon = mediaCardHideAppIcon,
                    onMediaCardHideAppIconChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_APP_ICON, checked)
                        mediaCardHideAppIcon = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_APP_ICON, checked)
                    },
                    mediaCardHideDeviceSwitch = mediaCardHideDeviceSwitch,
                    onMediaCardHideDeviceSwitchChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, checked)
                        mediaCardHideDeviceSwitch = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, checked)
                    },
                    lockscreenAllNotifications = lockscreenAllNotifications,
                    onLockscreenAllNotificationsChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, checked)
                        lockscreenAllNotifications = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, checked)
                    },
                    lockscreenKeepNotifications = lockscreenKeepNotifications,
                    onLockscreenKeepNotificationsChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, checked)
                        lockscreenKeepNotifications = checked
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, checked)
                    },
                    onPaModelSpoofEnabledChange = { checked ->
                        handlePaModelSpoofChange(checked)
                    },
                    showInSettings = showInSettings,
                    onShowInSettingsChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS, checked)
                        showInSettings = checked
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS, checked)
                    },
                    disableVideoRingback = disableVideoRingback,
                    onDisableVideoRingbackChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK, checked)
                        disableVideoRingback = checked
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK, checked)
                    },
                    hideLauncherIcon = hideLauncherIcon,
                    onHideLauncherIconChange = { checked ->
                        hideLauncherIcon = checked
                        // Component state changes touch PackageManager and must stay on the
                        // activity thread; only preference persistence is dispatched.
                        setLauncherIconVisible(activity, !checked)
                        coroutineScope.launch(Dispatchers.IO) {
                            settings.storage.putBoolean(AppSettingsKeys.KEY_HIDE_LAUNCHER_ICON, checked)
                        }
                    },
                    immediateMonetRefresh = immediateMonetRefresh,
                    onImmediateMonetRefreshChange = { enabled ->
                        immediateMonetRefresh = enabled
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_IMMEDIATE_MONET_REFRESH, enabled)
                    },
                    unlockThirdPartyDarkMode = unlockThirdPartyDarkMode,
                     onUnlockThirdPartyDarkModeChange = { checked ->
                         unlockThirdPartyDarkMode = checked
                         settings.storage.putBoolean(PlatformSettingsKeys.KEY_UNLOCK_THIRD_PARTY_DARK_MODE, checked)
                     },
                     disableSpatialAudio = disableSpatialAudio,
                    onDisableSpatialAudioChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO, checked)
                        disableSpatialAudio = checked
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO, checked)
                    },
                    forceAdaptiveAnc = forceAdaptiveAnc,
                    onForceAdaptiveAncChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC, checked)
                        forceAdaptiveAnc = checked
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC, checked)
                    },
                    disableMiTrustRiskMonitoring = disableMiTrustRiskMonitoring,
                    onDisableMiTrustRiskMonitoringChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING, checked)
                        disableMiTrustRiskMonitoring = checked
                        settings.storage.putBoolean(
                            PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING,
                            checked
                        )
                    },
                    disableGuardEnvironmentCheck = disableGuardEnvironmentCheck,
                    onDisableGuardEnvironmentCheckChange = { checked ->
                        disableGuardEnvironmentCheck = checked
                        settings.storage.putBoolean(
                            PlatformSettingsKeys.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK,
                            checked
                        )
                    },
                    blockGuardUploadAppList = blockGuardUploadAppList,
                    onBlockGuardUploadAppListChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST, checked)
                        blockGuardUploadAppList = checked
                        settings.storage.putBoolean(
                            PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST,
                            checked
                        )
                    },
                    blockMiLinkHpplayFiles = blockMiLinkHpplayFiles,
                    onBlockMiLinkHpplayFilesChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES, checked)
                        blockMiLinkHpplayFiles = checked
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES, checked)
                    },
                    allowThirdPartyTheme = allowThirdPartyTheme,
                    onAllowThirdPartyThemeChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME, checked)
                        allowThirdPartyTheme = checked
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME, checked)
                    },
                    removeIslandPlaceholder = removeIslandPlaceholder,
                    onRemoveIslandPlaceholderChange = { checked ->
                        removeIslandPlaceholder = checked
                        markTweaked(PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER, checked)
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER, checked)
                    },
                    threeIslands = threeIslands,
                    landscapeFullIslands = landscapeFullIslands,
                    landscapeMoreIslands = landscapeMoreIslands,
                    onLandscapeMoreIslandsChange = { checked ->
                        landscapeMoreIslands = checked
                        markTweaked(PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS, checked)
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS, checked)
                    },
                    onLandscapeFullIslandsChange = { checked ->
                        landscapeFullIslands = checked
                        markTweaked(PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS, checked)
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS, checked)
                    },
                    onThreeIslandsChange = { checked ->
                        threeIslands = checked
                        markTweaked(PlatformSettingsKeys.KEY_THREE_ISLANDS, checked)
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_THREE_ISLANDS, checked)
                    },
                    ccHideSuperIsland = ccHideSuperIsland,
                    islandLiveUpdateIcon = islandLiveUpdateIcon,
                    islandNarrowTimeFont = islandNarrowTimeFont,
                    onIslandLiveUpdateIconChange = { checked ->
                        islandLiveUpdateIcon = checked
                        markTweaked(PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON, checked)
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON, checked)
                    },
                    onIslandNarrowTimeFontChange = { checked ->
                        islandNarrowTimeFont = checked
                        markTweaked(PlatformSettingsKeys.KEY_ISLAND_NARROW_TIME_FONT, checked)
                        settings.storage.putBoolean(PlatformSettingsKeys.KEY_ISLAND_NARROW_TIME_FONT, checked)
                    },
                    onCcHideSuperIslandChange = { checked ->
                        ccHideSuperIsland = checked
                        markTweaked(SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND, checked)
                        settings.storage.putBoolean(SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND, checked)
                    },
                    focusNotificationUnlockWhitelist = focusNotificationUnlockWhitelist,
                    onFocusNotificationUnlockWhitelistChange = { checked ->
                        handleFocusNotificationUnlockWhitelistChange(checked)
                    },
                    mediaSuperIslandUnlockWhitelist = mediaSuperIslandUnlockWhitelist,
                    onMediaSuperIslandUnlockWhitelistChange = { checked ->
                        handleMediaSuperIslandUnlockWhitelistChange(checked)
                    },
                    xmsfUnlockFocusAuth = xmsfUnlockFocusAuth,
                    onXmsfUnlockFocusAuthChange = { checked ->
                        handleXmsfUnlockFocusAuthChange(checked)
                    },
                    backdrop = backdrop,
                    pageScale = pageScale,
                    onPageScaleChange = { scale ->
                        pageScale = scale
                        settings.storage.putFloat(AppSettingsKeys.KEY_PAGE_SCALE, scale)
                    },
                    onViewSourceCode = {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, "https://github.com/takekazex/HyperTweak".toUri())
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    },
                    onJoinTelegramGroup = {
                        try {
                            // The https form resolves to the Telegram app when it is installed (t.me is
                            // an app link) and falls back to a browser otherwise, so no tg:// scheme
                            // and no explicit package check is needed.
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, "https://t.me/PerlinkcaTweak".toUri())
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    },
                    onClearAllSettings = { restartAllScopes, restartHyperTweak ->
                        // clearAllSettings() waits for the actual daemon-backed commit receipt via
                        // commitRemoteMutation, so it must not run on the main thread: invoking it
                        // inline from the dialog's onClick froze the UI long enough to ANR.
                        coroutineScope.launch {
                            val cleared = withContext(Dispatchers.IO) { settings.storage.backup.clearAllSettings() }
                            if (!cleared) {
                                Toast.makeText(
                                    localizedContext,
                                    localizedContext.getString(R.string.settings_clear_all_failed),
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }
                            clearPendingRestartTracking()
                            val restartScopesJob = if (restartAllScopes) {
                                launch {
                                    settings.storage.flush()
                                    val allScopes = currentRestartableScopes(activity, services.scopes)
                                    services.restarts
                                        .restartScope(activity, coroutineScope, allScopes)
                                        .join()
                                }
                            } else {
                                null
                            }
                            if (restartHyperTweak) {
                                launch {
                                    restartScopesJob?.join()
                                    // Recreate so every Compose state reloads from the now-default prefs.
                                    activity.recreate()
                                }
                            }
                        }
                    },
                    onSettingsRestored = {
                        clearPendingRestartTracking()
                        setLauncherIconVisible(
                            activity,
                            !settings.storage.getBoolean(AppSettingsKeys.KEY_HIDE_LAUNCHER_ICON, false)
                        )
                        // Recreate so every Compose state reflects the restored configuration.
                        activity.recreate()
                    },
                    onRestartScope = { selection ->
                        // Push every queued setting to the daemon before the scoped processes die;
                        // their hookers read the daemon copy at (re)load time.
                        settings.storage.flush()
                        services.restarts.restartScope(activity, coroutineScope, selection, ::clearRestartedScopes)
                    },
                    onHotReload = {
                        // Hot reload re-attaches the module to the already running stale targets;
                        // it must remain independent from the normal force-stop/restart path.
                        settings.storage.flush()
                        services.module.hotReloadStaleTargets()
                    },
                    onRestartAllScopes = {
                        coroutineScope.launch {
                            settings.storage.flush()
                            val allScopes = currentRestartableScopes(activity, services.scopes)
                            services.restarts
                                .restartScope(activity, coroutineScope, allScopes, ::clearRestartedScopes)
                                .join()
                            // Refresh after the restart completes so a previously stale warning
                            // disappears without requiring a second app launch.
                            services.module.refreshHotReloadTargets()
                        }
                    },
                    appLanguage = appLanguage,
                    onAppLanguageChange = { lang ->
                        appLanguage = lang
                        settings.storage.putInt(AppSettingsKeys.KEY_LANGUAGE, lang)
                    },
                    onShortcutsChanged = {
                        coroutineScope.launch(Dispatchers.IO) {
                            runCatching { services.shortcuts.updateShortcuts(activity) }
                        }
                    },
                    updateManager = updateManager,
                    snackbarHostState = snackbarHostState
                    ))

                    // Hosted here, not inside the update page: the silent check runs at launch, so a
                    // prompt owned by that page would only ever be seen after navigating to it.
                    UpdateAvailablePrompt(updateManager)
                }
            }
        }
    }
}
