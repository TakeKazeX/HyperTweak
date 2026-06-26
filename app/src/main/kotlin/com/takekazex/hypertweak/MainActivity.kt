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

private val TWEAK_RESTART_SCOPES = mapOf(
    Preferences.KEY_AOD_FULLSCREEN to RestartScopeSelection(
        systemUi = true,
        settings = true,
        aod = true
    ),
    Preferences.KEY_HIDE_FINGERPRINT to RestartScopeSelection(systemUi = true),
    Preferences.KEY_HIDE_GESTURE_BAR to RestartScopeSelection(systemUi = true),
    Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT to RestartScopeSelection(systemUi = true),
    Preferences.KEY_SLIDER_SHOW_PERCENTAGE to RestartScopeSelection(systemUi = true),
    Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE to RestartScopeSelection(systemUi = true),
    Preferences.KEY_SHOW_IN_SETTINGS to RestartScopeSelection(settings = true),
    Preferences.KEY_UNLOCK_PASSKEY to RestartScopeSelection(
        settings = true,
        securityCenter = true,
        scanner = true
    ),
    Preferences.KEY_DISABLE_SPATIAL_AUDIO to RestartScopeSelection(
        settings = true,
        milink = true
    ),
    Preferences.KEY_FORCE_ADAPTIVE_ANC to RestartScopeSelection(bluetooth = true),
    Preferences.KEY_FCM_LIVE_ENABLED to RestartScopeSelection(powerkeeper = true)
)

private val ALL_MANUAL_RESTART_SCOPES = TWEAK_RESTART_SCOPES.values.fold(RestartScopeSelection.Empty) { acc, scopes ->
    acc.merge(scopes)
}

private const val KEY_PENDING_RESTART_BOOT_TOKEN = "pending_restart_boot_token"
private const val KEY_DIRTY_TWEAK_KEYS = "dirty_tweak_keys"
private const val KEY_TWEAK_BASELINE_PREFIX = "tweak_baseline_"

private fun currentBootToken(): String {
    return runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: ((System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 1000L).toString()
}

private fun restartScopesForDirtyTweaks(keys: Set<String>): RestartScopeSelection {
    return keys.fold(RestartScopeSelection.Empty) { acc, key ->
        acc.merge(TWEAK_RESTART_SCOPES[key] ?: RestartScopeSelection.Empty)
    }
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
            var dirtyTweakKeys by remember {
                val storedBootToken = localPrefs.getString(KEY_PENDING_RESTART_BOOT_TOKEN, null)
                if (storedBootToken == bootToken) {
                    mutableStateOf(localPrefs.getStringSet(KEY_DIRTY_TWEAK_KEYS, emptySet()).orEmpty())
                } else {
                    localPrefs.edit {
                        remove(Preferences.KEY_PENDING_RESTART_SCOPES)
                        remove(KEY_DIRTY_TWEAK_KEYS)
                        TWEAK_RESTART_SCOPES.keys.forEach { remove("$KEY_TWEAK_BASELINE_PREFIX$it") }
                        putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
                    }
                    mutableStateOf(emptySet())
                }
            }
            var pendingRestartScopes by remember {
                val storedBootToken = localPrefs.getString(KEY_PENDING_RESTART_BOOT_TOKEN, null)
                if (storedBootToken == bootToken) {
                    mutableStateOf(
                        RestartScopeSelection.fromKeySet(
                            localPrefs.getStringSet(Preferences.KEY_PENDING_RESTART_SCOPES, emptySet()).orEmpty()
                        ).intersect(restartScopesForDirtyTweaks(dirtyTweakKeys))
                    )
                } else {
                    mutableStateOf(RestartScopeSelection.Empty)
                }
            }

            fun effectivePendingRestartScopes(dirtyKeys: Set<String>, pendingScopes: RestartScopeSelection): RestartScopeSelection {
                return restartScopesForDirtyTweaks(dirtyKeys).intersect(pendingScopes)
            }

            fun updateDirtyTweakKeys(next: Set<String>) {
                val nextPendingScopes = effectivePendingRestartScopes(next, pendingRestartScopes)
                dirtyTweakKeys = next
                pendingRestartScopes = nextPendingScopes
                localPrefs.edit {
                    putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
                    putStringSet(KEY_DIRTY_TWEAK_KEYS, next)
                    putStringSet(Preferences.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
                }
            }

            fun currentTweakValue(key: String): Boolean {
                return when (key) {
                    Preferences.KEY_AOD_FULLSCREEN -> aodFullscreen
                    Preferences.KEY_HIDE_FINGERPRINT -> hideFingerprint
                    Preferences.KEY_HIDE_GESTURE_BAR -> hideGestureBar
                    Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT -> gestureBarRaiseLayout
                    Preferences.KEY_SLIDER_SHOW_PERCENTAGE -> sliderShowPercentage
                    Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE -> sliderSamePercentageStyle
                    Preferences.KEY_SHOW_IN_SETTINGS -> showInSettings
                    Preferences.KEY_UNLOCK_PASSKEY -> unlockPasskey
                    Preferences.KEY_DISABLE_SPATIAL_AUDIO -> disableSpatialAudio
                    Preferences.KEY_FORCE_ADAPTIVE_ANC -> forceAdaptiveAnc
                    Preferences.KEY_FCM_LIVE_ENABLED -> fcmLiveEnabled
                    else -> Preferences.getBoolean(key, false)
                }
            }

            fun markTweaked(key: String, value: Boolean) {
                val baselineKey = "$KEY_TWEAK_BASELINE_PREFIX$key"
                val baseline = if (localPrefs.contains(baselineKey)) {
                    localPrefs.getBoolean(baselineKey, value)
                } else {
                    Preferences.getBoolean(key, value)
                }
                val nextDirtyKeys = if (value == baseline) {
                    dirtyTweakKeys - key
                } else {
                    dirtyTweakKeys + key
                }
                val nextPendingScopes = if (value == baseline) {
                    effectivePendingRestartScopes(nextDirtyKeys, pendingRestartScopes)
                } else {
                    pendingRestartScopes.merge(TWEAK_RESTART_SCOPES[key] ?: RestartScopeSelection.Empty)
                }

                dirtyTweakKeys = nextDirtyKeys
                pendingRestartScopes = nextPendingScopes
                localPrefs.edit {
                    putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
                    putBoolean(baselineKey, baseline)
                    putStringSet(KEY_DIRTY_TWEAK_KEYS, nextDirtyKeys)
                    putStringSet(Preferences.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
                }
            }

            fun clearRestartedScopes(scopes: RestartScopeSelection) {
                val nextPendingScopes = pendingRestartScopes.without(scopes)
                val clearedKeys = dirtyTweakKeys.filter { key ->
                    TWEAK_RESTART_SCOPES[key]?.let(nextPendingScopes::intersect)?.isEmpty() == true
                }.toSet()
                val nextDirtyKeys = dirtyTweakKeys - clearedKeys
                dirtyTweakKeys = nextDirtyKeys
                pendingRestartScopes = nextPendingScopes
                localPrefs.edit {
                    putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
                    clearedKeys.forEach { key ->
                        putBoolean("$KEY_TWEAK_BASELINE_PREFIX$key", currentTweakValue(key))
                    }
                    putStringSet(KEY_DIRTY_TWEAK_KEYS, nextDirtyKeys)
                    putStringSet(Preferences.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
                }
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
                        markTweaked(Preferences.KEY_AOD_FULLSCREEN, checked)
                        aodFullscreen = checked
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
                        markTweaked(Preferences.KEY_HIDE_FINGERPRINT, checked)
                        hideFingerprint = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_HIDE_FINGERPRINT, checked)
                        }
                    },
                    hideGestureBar = hideGestureBar,
                    onHideGestureBarChange = { checked ->
                        markTweaked(Preferences.KEY_HIDE_GESTURE_BAR, checked)
                        hideGestureBar = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_HIDE_GESTURE_BAR, checked)
                        }
                    },
                    gestureBarRaiseLayout = gestureBarRaiseLayout,
                    onGestureBarRaiseLayoutChange = { checked ->
                        markTweaked(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, checked)
                        gestureBarRaiseLayout = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, checked)
                        }
                    },
                    sliderShowPercentage = sliderShowPercentage,
                    onSliderShowPercentageChange = { checked ->
                        markTweaked(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, checked)
                        sliderShowPercentage = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, checked)
                        }
                    },
                    sliderSamePercentageStyle = sliderSamePercentageStyle,
                    onSliderSamePercentageChange = { checked ->
                        markTweaked(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, checked)
                        sliderSamePercentageStyle = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, checked)
                        }
                    },
                    showInSettings = showInSettings,
                    onShowInSettingsChange = { checked ->
                        markTweaked(Preferences.KEY_SHOW_IN_SETTINGS, checked)
                        showInSettings = checked
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
                        markTweaked(Preferences.KEY_UNLOCK_PASSKEY, checked)
                        unlockPasskey = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_UNLOCK_PASSKEY, checked)
                        }
                    },
                    disableSpatialAudio = disableSpatialAudio,
                    onDisableSpatialAudioChange = { checked ->
                        markTweaked(Preferences.KEY_DISABLE_SPATIAL_AUDIO, checked)
                        disableSpatialAudio = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, checked)
                        }
                    },
                    forceAdaptiveAnc = forceAdaptiveAnc,
                    onForceAdaptiveAncChange = { checked ->
                        markTweaked(Preferences.KEY_FORCE_ADAPTIVE_ANC, checked)
                        forceAdaptiveAnc = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_FORCE_ADAPTIVE_ANC, checked)
                        }
                    },
                    fcmLiveEnabled = fcmLiveEnabled,
                    onFcmLiveEnabledChange = { checked ->
                        markTweaked(Preferences.KEY_FCM_LIVE_ENABLED, checked)
                        fcmLiveEnabled = checked
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
