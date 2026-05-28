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
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.ui.navigation.HyperTweakNavContainer
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        enableEdgeToEdge()

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

            val serviceConnected by XposedServiceManager.serviceFlow.collectAsState()

            // State variables for toggles
            var aodFullscreen by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOD_FULLSCREEN, false)) }
            var removeGms by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)) }
            var hideFingerprint by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_HIDE_FINGERPRINT, false)) }
            var showInSettings by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_SHOW_IN_SETTINGS, false)) }
            var hideLauncherIcon by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_HIDE_LAUNCHER_ICON, false)) }
            var sliderShowPercentage by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) }
            var sliderSamePercentageStyle by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)) }

            val coroutineScope = rememberCoroutineScope()

            LaunchedEffect(allowLandscape) {
                requestedOrientation = if (allowLandscape) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }

            val context = androidx.compose.ui.platform.LocalContext.current
            val moduleActive = isModuleActive() || serviceConnected != null
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
                    backdrop = backdrop,
                    onViewSourceCode = {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/takekazex/HyperTweak"))
                            this@MainActivity.startActivity(intent)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                )
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
