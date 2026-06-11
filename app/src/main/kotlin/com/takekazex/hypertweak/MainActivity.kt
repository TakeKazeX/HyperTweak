package com.takekazex.hypertweak

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import com.takekazex.hypertweak.util.LocaleHelper
import androidx.compose.ui.platform.LocalContext

internal fun getSystemAccentColor(context: Context): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            context.getColor(android.R.color.system_accent1_500)
        } catch (e: Throwable) {
            0xFF007AFF.toInt()
        }
    } else {
        0xFF007AFF.toInt()
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
                    val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        "android.telephony.action.SECRET_CODE"
                    else "android.provider.Telephony.SECRET_CODE"
                    Runtime.getRuntime().exec("su").outputStream.bufferedWriter().use { w ->
                        w.write("am broadcast -a $action -d android_secret_code://5776733\nexit\n")
                        w.flush()
                    }
                } catch (_: Exception) {}
            }.start()
            finish()
            return
        }

        enableEdgeToEdge()

        com.takekazex.hypertweak.util.ShortcutUtils.updateShortcuts(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            // Theme settings states
            var themeMode by remember { mutableStateOf(Preferences.getInt(Preferences.KEY_THEME_MODE, 0)) }
            var useMonet by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_USE_MONET, false)) }
            var seedColorHex by remember { mutableStateOf(Preferences.getInt(Preferences.KEY_SEED_COLOR, 0xFF007AFF.toInt())) }
            var useFloatingBottomBar by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_USE_FLOATING_BOTTOM_BAR, false)) }
            var floatingBarStyle by remember { mutableStateOf(Preferences.getInt(Preferences.KEY_FLOATING_BAR_STYLE, 0)) }
            var predictiveBackStyle by remember { mutableStateOf(Preferences.getInt(Preferences.KEY_PREDICTIVE_BACK_STYLE, 1)) }
            var predictiveBackFollowGesture by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, true)) }
            var allowLandscape by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_ALLOW_LANDSCAPE, false)) }
            var pageScale by remember { mutableStateOf(Preferences.getFloat(Preferences.KEY_PAGE_SCALE, 1.0f)) }
            var appLanguage by remember { mutableStateOf(Preferences.getInt(Preferences.KEY_LANGUAGE, 0)) }

            val serviceConnected by XposedServiceManager.serviceFlow.collectAsState()

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
            val lastActive = remember { localPrefs.getBoolean("last_known_module_activated", false) }
            val initialActive = isModuleActive() || lastActive
            var moduleActive by remember { mutableStateOf(initialActive) }

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
                }

                if (isModuleActive()) {
                    moduleActive = true
                    localPrefs.edit().putBoolean("last_known_module_activated", true).apply()
                    reloadAllPreferences()
                    return@LaunchedEffect
                }

                if (serviceConnected != null) {
                    moduleActive = true
                    localPrefs.edit().putBoolean("last_known_module_activated", true).apply()
                    reloadAllPreferences()
                } else {
                    // Wait 500ms to allow the Xposed service binding to finish
                    kotlinx.coroutines.delay(500)
                    if (XposedServiceManager.currentService == null) {
                        moduleActive = false
                        localPrefs.edit().putBoolean("last_known_module_activated", false).apply()
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
                    aodFullscreen = aodFullscreen,
                    onAodFullscreenChange = { checked ->
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
                        hideFingerprint = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_HIDE_FINGERPRINT, checked)
                        }
                    },
                    hideGestureBar = hideGestureBar,
                    onHideGestureBarChange = { checked ->
                        hideGestureBar = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_HIDE_GESTURE_BAR, checked)
                        }
                    },
                    gestureBarRaiseLayout = gestureBarRaiseLayout,
                    onGestureBarRaiseLayoutChange = { checked ->
                        gestureBarRaiseLayout = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, checked)
                        }
                    },
                    sliderShowPercentage = sliderShowPercentage,
                    onSliderShowPercentageChange = { checked ->
                        sliderShowPercentage = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, checked)
                        }
                    },
                    sliderSamePercentageStyle = sliderSamePercentageStyle,
                    onSliderSamePercentageChange = { checked ->
                        sliderSamePercentageStyle = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, checked)
                        }
                    },
                    showInSettings = showInSettings,
                    onShowInSettingsChange = { checked ->
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
                        unlockPasskey = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_UNLOCK_PASSKEY, checked)
                        }
                    },
                    disableSpatialAudio = disableSpatialAudio,
                    onDisableSpatialAudioChange = { checked ->
                        disableSpatialAudio = checked
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, checked)
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
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/takekazex/HyperTweak"))
                            this@MainActivity.startActivity(intent)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    },
                    onRestartScope = { systemUi, settings, aod, securityCenter, scanner ->
                        RestartUtils.restartScope(this@MainActivity, coroutineScope, systemUi, settings, aod, securityCenter, scanner)
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
