package com.takekazex.hypertweak

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.edit
import androidx.core.net.toUri
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.ui.navigation.HyperTweakNavContainer
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.takekazex.hypertweak.util.RestartUtils
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.LocaleHelper
import androidx.compose.ui.platform.LocalContext

internal fun getSystemAccentColor(context: Context): Int {
    return try {
        context.getColor(android.R.color.system_accent1_500)
    } catch (e: Throwable) {
        0xFF007AFF.toInt()
    }
}

private val AOD_RESTART_SCOPES = RestartScopeSelection(
    systemUi = true,
    settings = true,
    aod = true
)

private val SYSTEM_UI_RESTART_SCOPES = RestartScopeSelection(systemUi = true)

private val SETTINGS_RESTART_SCOPES = RestartScopeSelection(settings = true)

private val PASSKEY_RESTART_SCOPES = RestartScopeSelection(
    settings = true,
    securityCenter = true,
    scanner = true
)

private val SPATIAL_AUDIO_RESTART_SCOPES = RestartScopeSelection(
    settings = true,
    milink = true
)

private val ADAPTIVE_ANC_RESTART_SCOPES = RestartScopeSelection(bluetooth = true)

private val FCM_LIVE_RESTART_SCOPES = RestartScopeSelection(powerkeeper = true)

private val ALL_MANUAL_RESTART_SCOPES = RestartScopeSelection(
    systemUi = true,
    settings = true,
    aod = true,
    securityCenter = true,
    scanner = true,
    milink = true,
    bluetooth = true,
    powerkeeper = true
)

private const val KEY_PENDING_RESTART_BOOT_TOKEN = "pending_restart_boot_token"

private fun currentBootToken(): String {
    return runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: ((System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 1000L).toString()
}

class MainActivity : ComponentActivity() {

    // Intercepted by ModuleStatusHooker. Keep annotation prevents R8 optimization/inlining.
    @Keep
    fun isModuleActive(): Boolean {
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shortcutTarget = intent?.getStringExtra("shortcut_target")
        if (shortcutTarget == "lsposed") {
            Thread {
                try {
                    Runtime.getRuntime().exec("su").outputStream.bufferedWriter().use { w ->
                        w.write("am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733\nexit\n")
                        w.flush()
                    }
                } catch (_: Exception) {}
            }.start()
            finish()
            return
        }

        enableEdgeToEdge()

        com.takekazex.hypertweak.util.ShortcutUtils.updateShortcuts(this)

        window.isNavigationBarContrastEnforced = false

        setContent {
            // Theme settings states
            var themeMode by remember { mutableIntStateOf(Preferences.getInt(Preferences.KEY_THEME_MODE, 0)) }
            var useMonet by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_USE_MONET, false)) }
            var seedColorHex by remember { mutableIntStateOf(Preferences.getInt(Preferences.KEY_SEED_COLOR, 0xFF007AFF.toInt())) }
            var useFloatingBottomBar by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_USE_FLOATING_BOTTOM_BAR, false)) }
            var floatingBarStyle by remember { mutableIntStateOf(Preferences.getInt(Preferences.KEY_FLOATING_BAR_STYLE, 0)) }
            var predictiveBackStyle by remember { mutableIntStateOf(Preferences.getInt(Preferences.KEY_PREDICTIVE_BACK_STYLE, 1)) }
            var predictiveBackFollowGesture by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, true)) }
            var allowLandscape by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_ALLOW_LANDSCAPE, false)) }
            var pageScale by remember { mutableFloatStateOf(Preferences.getFloat(Preferences.KEY_PAGE_SCALE, 1.0f)) }
            var appLanguage by remember { mutableIntStateOf(Preferences.getInt(Preferences.KEY_LANGUAGE, 0)) }

            val serviceConnected by XposedServiceManager.serviceFlow.collectAsState()
            val staleTargets by XposedServiceManager.staleTargetsFlow.collectAsState()
            val hotReloading by XposedServiceManager.hotReloadingFlow.collectAsState()
            val hotReloadReport by XposedServiceManager.hotReloadReportFlow.collectAsState()

            // State variables for toggles
            var aodFullscreen by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOD_FULLSCREEN, false)) }
            var removeGms by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)) }
            var hideFingerprint by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_HIDE_FINGERPRINT, false)) }
            var showInSettings by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_SHOW_IN_SETTINGS, false)) }
            var hideGestureBar by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false)) }
            var gestureBarRaiseLayout by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, false)) }
            var hideLauncherIcon by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_HIDE_LAUNCHER_ICON, false)) }
            var sliderShowPercentage by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) }
            var sliderSamePercentageStyle by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)) }
            var unlockPasskey by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_UNLOCK_PASSKEY, false)) }
            var disableSpatialAudio by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)) }
            var forceAdaptiveAnc by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_FORCE_ADAPTIVE_ANC, false)) }
            var fcmLiveEnabled by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_FCM_LIVE_ENABLED, false)) }

            val coroutineScope = rememberCoroutineScope()

            LaunchedEffect(allowLandscape) {
                requestedOrientation = if (allowLandscape) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }

            val context = androidx.compose.ui.platform.LocalContext.current
            val localPrefs = remember { getSharedPreferences(Preferences.NAME, Context.MODE_PRIVATE) }
            val bootToken = remember { currentBootToken() }
            val lastActive = remember { localPrefs.getBoolean("last_known_module_activated", false) }
            val initialActive = isModuleActive() || lastActive
            var moduleActive by remember { mutableStateOf(initialActive) }
            var pendingRestartScopes by remember {
                val storedBootToken = localPrefs.getString(KEY_PENDING_RESTART_BOOT_TOKEN, null)
                if (storedBootToken == bootToken) {
                    mutableStateOf(
                        RestartScopeSelection.fromKeySet(
                            localPrefs.getStringSet(Preferences.KEY_PENDING_RESTART_SCOPES, emptySet()).orEmpty()
                        )
                    )
                } else {
                    localPrefs.edit {
                        remove(Preferences.KEY_PENDING_RESTART_SCOPES)
                        putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
                    }
                    mutableStateOf(RestartScopeSelection.Empty)
                }
            }

            fun updatePendingRestartScopes(next: RestartScopeSelection) {
                pendingRestartScopes = next
                localPrefs.edit {
                    putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
                    putStringSet(Preferences.KEY_PENDING_RESTART_SCOPES, next.toKeySet())
                }
            }

            fun markPendingRestartScopes(scopes: RestartScopeSelection) {
                updatePendingRestartScopes(pendingRestartScopes.merge(scopes))
            }

            fun clearRestartedScopes(scopes: RestartScopeSelection) {
                updatePendingRestartScopes(pendingRestartScopes.without(scopes))
            }

            LaunchedEffect(serviceConnected) {
                fun reloadAllPreferences() {
                    themeMode = Preferences.getInt(Preferences.KEY_THEME_MODE, 0)
                    useMonet = Preferences.getBoolean(Preferences.KEY_USE_MONET, false)
                    seedColorHex = Preferences.getInt(Preferences.KEY_SEED_COLOR, 0xFF007AFF.toInt())
                    useFloatingBottomBar = Preferences.getBoolean(Preferences.KEY_USE_FLOATING_BOTTOM_BAR, false)
                    floatingBarStyle = Preferences.getInt(Preferences.KEY_FLOATING_BAR_STYLE, 0)
                    predictiveBackStyle = Preferences.getInt(Preferences.KEY_PREDICTIVE_BACK_STYLE, 1)
                    predictiveBackFollowGesture = Preferences.getBoolean(Preferences.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, true)
                    allowLandscape = Preferences.getBoolean(Preferences.KEY_ALLOW_LANDSCAPE, false)
                    pageScale = Preferences.getFloat(Preferences.KEY_PAGE_SCALE, 1.0f)
                    appLanguage = Preferences.getInt(Preferences.KEY_LANGUAGE, 0)
                    aodFullscreen = Preferences.getBoolean(Preferences.KEY_AOD_FULLSCREEN, false)
                    removeGms = Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)
                    hideFingerprint = Preferences.getBoolean(Preferences.KEY_HIDE_FINGERPRINT, false)
                    showInSettings = Preferences.getBoolean(Preferences.KEY_SHOW_IN_SETTINGS, false)
                    hideGestureBar = Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false)
                    gestureBarRaiseLayout = Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, false)
                    hideLauncherIcon = Preferences.getBoolean(Preferences.KEY_HIDE_LAUNCHER_ICON, false)
                    sliderShowPercentage = Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)
                    sliderSamePercentageStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                    unlockPasskey = Preferences.getBoolean(Preferences.KEY_UNLOCK_PASSKEY, false)
                    disableSpatialAudio = Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)
                    forceAdaptiveAnc = Preferences.getBoolean(Preferences.KEY_FORCE_ADAPTIVE_ANC, false)
                    fcmLiveEnabled = Preferences.getBoolean(Preferences.KEY_FCM_LIVE_ENABLED, false)
                }

                if (isModuleActive()) {
                    moduleActive = true
                    localPrefs.edit { putBoolean("last_known_module_activated", true) }
                    reloadAllPreferences()
                    XposedServiceManager.refreshHotReloadTargets()
                    return@LaunchedEffect
                }

                if (serviceConnected != null) {
                    moduleActive = true
                    localPrefs.edit { putBoolean("last_known_module_activated", true) }
                    reloadAllPreferences()
                    XposedServiceManager.refreshHotReloadTargets()
                } else {
                    // Wait 500ms to allow the Xposed service binding to finish
                    kotlinx.coroutines.delay(500)
                    if (XposedServiceManager.currentService == null) {
                        moduleActive = false
                        localPrefs.edit { putBoolean("last_known_module_activated", false) }
                    }
                }
            }

            val isDark = isSystemInDarkTheme()
            val resolvedSeedColorHex = remember(seedColorHex, context) {
                if (seedColorHex == 0) {
                    getSystemAccentColor(context)
                } else {
                    seedColorHex
                }
            }

            val controller = remember(themeMode, useMonet, resolvedSeedColorHex, isDark) {
                val mode = when (themeMode) {
                    1 -> if (useMonet) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
                    2 -> if (useMonet) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
                    else -> if (useMonet) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
                }
                ThemeController(
                    colorSchemeMode = mode,
                    keyColor = Color(resolvedSeedColorHex),
                    isDark = when (themeMode) {
                        1 -> false
                        2 -> true
                        else -> null
                    }
                )
            }

            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, pageScale) {
                Density(systemDensity.density * pageScale, systemDensity.fontScale)
            }
            val localizedContext = remember(context, appLanguage) {
                LocaleHelper.getLocalizedContext(context, appLanguage)
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalDensity provides density
            ) {
                MiuixTheme(controller = controller) {
                    val surfaceColor = MiuixTheme.colorScheme.surface
                    val backdrop = rememberLayerBackdrop {
                        drawRect(surfaceColor)
                        drawContent()
                    }

                    HyperTweakNavContainer(
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putInt(Preferences.KEY_THEME_MODE, mode)
                        }
                    },
                    useMonet = useMonet,
                    onUseMonetChange = { monet ->
                        useMonet = monet
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_USE_MONET, monet)
                        }
                    },
                    seedColorHex = seedColorHex,
                    onSeedColorChange = { color ->
                        seedColorHex = color
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putInt(Preferences.KEY_SEED_COLOR, color)
                        }
                    },
                    useFloatingBottomBar = useFloatingBottomBar,
                    onUseFloatingBottomBarChange = { floating ->
                        useFloatingBottomBar = floating
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_USE_FLOATING_BOTTOM_BAR, floating)
                        }
                    },
                    floatingBarStyle = floatingBarStyle,
                    onFloatingBarStyleChange = { style ->
                        floatingBarStyle = style
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putInt(Preferences.KEY_FLOATING_BAR_STYLE, style)
                        }
                    },
                    predictiveBackStyle = predictiveBackStyle,
                    onPredictiveBackStyleChange = { style ->
                        predictiveBackStyle = style
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putInt(Preferences.KEY_PREDICTIVE_BACK_STYLE, style)
                        }
                    },
                    predictiveBackFollowGesture = predictiveBackFollowGesture,
                    onPredictiveBackFollowGestureChange = { follow ->
                        predictiveBackFollowGesture = follow
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, follow)
                        }
                    },
                    allowLandscape = allowLandscape,
                    onAllowLandscapeChange = { allowed ->
                        allowLandscape = allowed
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_ALLOW_LANDSCAPE, allowed)
                        }
                    },
                    moduleActive = moduleActive,
                    hotReloadAvailable = staleTargets.isNotEmpty(),
                    hotReloading = hotReloading,
                    hotReloadTargets = staleTargets.map { it.processName },
                    hotReloadReport = hotReloadReport,
                    pendingRestartScopes = pendingRestartScopes,
                    aodFullscreen = aodFullscreen,
                    onAodFullscreenChange = { checked ->
                        aodFullscreen = checked
                        markPendingRestartScopes(AOD_RESTART_SCOPES)
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_AOD_FULLSCREEN, checked)
                        }
                    },
                    removeGms = removeGms,
                    onRemoveGmsChange = { checked ->
                        removeGms = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, checked)
                        }
                    },
                    hideFingerprint = hideFingerprint,
                    onHideFingerprintChange = { checked ->
                        hideFingerprint = checked
                        markPendingRestartScopes(SYSTEM_UI_RESTART_SCOPES)
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_HIDE_FINGERPRINT, checked)
                        }
                    },
                    hideGestureBar = hideGestureBar,
                    onHideGestureBarChange = { checked ->
                        hideGestureBar = checked
                        markPendingRestartScopes(SYSTEM_UI_RESTART_SCOPES)
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_HIDE_GESTURE_BAR, checked)
                        }
                    },
                    gestureBarRaiseLayout = gestureBarRaiseLayout,
                    onGestureBarRaiseLayoutChange = { checked ->
                        gestureBarRaiseLayout = checked
                        markPendingRestartScopes(SYSTEM_UI_RESTART_SCOPES)
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, checked)
                        }
                    },
                    sliderShowPercentage = sliderShowPercentage,
                    onSliderShowPercentageChange = { checked ->
                        sliderShowPercentage = checked
                        markPendingRestartScopes(SYSTEM_UI_RESTART_SCOPES)
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, checked)
                        }
                    },
                    sliderSamePercentageStyle = sliderSamePercentageStyle,
                    onSliderSamePercentageChange = { checked ->
                        sliderSamePercentageStyle = checked
                        markPendingRestartScopes(SYSTEM_UI_RESTART_SCOPES)
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, checked)
                        }
                    },
                    showInSettings = showInSettings,
                    onShowInSettingsChange = { checked ->
                        showInSettings = checked
                        markPendingRestartScopes(SETTINGS_RESTART_SCOPES)
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_SHOW_IN_SETTINGS, checked)
                        }
                    },
                    hideLauncherIcon = hideLauncherIcon,
                    onHideLauncherIconChange = { checked ->
                        hideLauncherIcon = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_HIDE_LAUNCHER_ICON, checked)
                            setLauncherIconVisible(this@MainActivity, !checked)
                        }
                    },
                    unlockPasskey = unlockPasskey,
                    onUnlockPasskeyChange = { checked ->
                        unlockPasskey = checked
                        markPendingRestartScopes(PASSKEY_RESTART_SCOPES)
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_UNLOCK_PASSKEY, checked)
                        }
                    },
                    disableSpatialAudio = disableSpatialAudio,
                    onDisableSpatialAudioChange = { checked ->
                        disableSpatialAudio = checked
                        markPendingRestartScopes(SPATIAL_AUDIO_RESTART_SCOPES)
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, checked)
                        }
                    },
                    forceAdaptiveAnc = forceAdaptiveAnc,
                    onForceAdaptiveAncChange = { checked ->
                        forceAdaptiveAnc = checked
                        markPendingRestartScopes(ADAPTIVE_ANC_RESTART_SCOPES)
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_FORCE_ADAPTIVE_ANC, checked)
                        }
                    },
                    fcmLiveEnabled = fcmLiveEnabled,
                    onFcmLiveEnabledChange = { checked ->
                        fcmLiveEnabled = checked
                        markPendingRestartScopes(FCM_LIVE_RESTART_SCOPES)
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_FCM_LIVE_ENABLED, checked)
                        }
                    },
                    backdrop = backdrop,
                    pageScale = pageScale,
                    onPageScaleChange = { scale ->
                        pageScale = scale
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putFloat(Preferences.KEY_PAGE_SCALE, scale)
                        }
                    },
                    onViewSourceCode = {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, "https://github.com/takekazex/HyperTweak".toUri())
                            this@MainActivity.startActivity(intent)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    },
                    onRestartScope = { selection ->
                        RestartUtils.restartScope(this@MainActivity, coroutineScope, selection)
                        clearRestartedScopes(selection)
                    },
                    onHotReload = { restartAllScopes ->
                        XposedServiceManager.hotReloadStaleTargets { report ->
                            if (restartAllScopes && report.failedCount == 0) {
                                RestartUtils.restartScope(this@MainActivity, coroutineScope, ALL_MANUAL_RESTART_SCOPES)
                                clearRestartedScopes(ALL_MANUAL_RESTART_SCOPES)
                            }
                        }
                    },
                    appLanguage = appLanguage,
                    onAppLanguageChange = { lang ->
                        appLanguage = lang
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putInt(Preferences.KEY_LANGUAGE, lang)
                        }
                    },
                    onShortcutsChanged = {
                        coroutineScope.launch(Dispatchers.IO) {
                            runCatching { com.takekazex.hypertweak.util.ShortcutUtils.updateShortcuts(this@MainActivity) }
                        }
                    }
                )
            }
        }
    }
}

    private fun setLauncherIconVisible(context: Context, visible: Boolean) {
        try {
            val pm = context.packageManager
            val componentName = ComponentName(context, "com.takekazex.hypertweak.MainActivityAlias")
            val state = if (visible) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP)
        } catch (e: Exception) {
            // Ignore
        }
    }
}
