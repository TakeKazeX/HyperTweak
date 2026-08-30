package com.takekazex.hypertweak

import android.annotation.SuppressLint
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.edit
import androidx.core.net.toUri
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarAction
import com.takekazex.hypertweak.hook.rules.googleapp.GoogleAppLiveTranslateHooker
import android.widget.Toast
import com.takekazex.hypertweak.ui.navigation.HyperTweakNavContainer
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.takekazex.hypertweak.ui.theme.MiuixSpec2025Adapter
import com.takekazex.hypertweak.ui.theme.rememberDeviceAccentColor
import com.takekazex.hypertweak.ui.theme.isEffectivelyDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.takekazex.hypertweak.util.RestartUtils
import com.takekazex.hypertweak.util.ScopeManager
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.LauncherVersion
import com.takekazex.hypertweak.util.PlatformLevel
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
    Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR to RestartScopeSelection(systemUi = true),
    Preferences.KEY_LOCKSCREEN_FINGERPRINT_AVOID to RestartScopeSelection(systemUi = true),
    Preferences.KEY_HIDE_GESTURE_BAR to RestartScopeSelection(systemUi = true),
    Preferences.KEY_MIUI_BACK_GESTURE_HOOK to RestartScopeSelection(
        systemUi = true,
        miuiHome = true
    ),
    Preferences.KEY_CROSS_TASK_WALLPAPER_BACKGROUND to RestartScopeSelection(systemUi = true),
    // Installs or removes the launcher-side hooks, so only the launcher has to come back.
    Preferences.KEY_AOSP_BACK_MIUI_HOME_HOOKS to RestartScopeSelection(miuiHome = true),
    Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT to RestartScopeSelection(systemUi = true),
    Preferences.KEY_GESTURE_BAR_ACTIONS_ENABLED to RestartScopeSelection(systemUi = true),
    Preferences.KEY_SLIDER_SHOW_PERCENTAGE to RestartScopeSelection(systemUi = true),
    Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE to RestartScopeSelection(systemUi = true),
    // The four per-element dp values are Float-typed prefs; only the Boolean master switch is
    // tracked here (markTweaked stores Boolean baselines — Float keys would crash the baseline
    // read in clearRestartedScopes). Changing a slider takes effect on the next SystemUI restart,
    // which the master-switch restart dialog or the manual scope restart already covers.
    Preferences.KEY_CC_CORNER_ENABLED to RestartScopeSelection(systemUi = true),
    // The editor-cards hooks install at control-center plugin load; only enabling needs the
    // restart (callbacks read the switch live, so disabling applies immediately).
    Preferences.KEY_CC_EDIT_ENABLED to RestartScopeSelection(systemUi = true),
    // Same for the element-size hooks; the individual size keys are read live on every bind.
    Preferences.KEY_CC_RESIZE_ENABLED to RestartScopeSelection(systemUi = true),
    Preferences.KEY_MEDIA_CARD_HIDE_APP_ICON to RestartScopeSelection(systemUi = true),
    Preferences.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH to RestartScopeSelection(systemUi = true),
    Preferences.KEY_LOCKSCREEN_ALL_NOTIFICATIONS to RestartScopeSelection(systemUi = true),
    Preferences.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS to RestartScopeSelection(systemUi = true),
    Preferences.KEY_SHOW_IN_SETTINGS to RestartScopeSelection(settings = true),
    Preferences.KEY_UNLOCK_PASSKEY to RestartScopeSelection(
        settings = true,
        securityCenter = true,
        scanner = true
    ),
    Preferences.KEY_DISABLE_SPATIAL_AUDIO to RestartScopeSelection(
        settings = true,
        milink = true,
        bluetooth = true
    ),
    Preferences.KEY_FORCE_ADAPTIVE_ANC to RestartScopeSelection(
        settings = true,
        milink = true,
        bluetooth = true
    ),
    Preferences.KEY_FCM_LIVE_ENABLED to RestartScopeSelection(powerkeeper = true),
    // The Quick Share phenotype override lives in Google Play services; GMS is a declared
    // required scope entry, so the toggle flows through the standard Home restart button.
    Preferences.KEY_QUICK_SHARE_ENABLED to RestartScopeSelection(gms = true),
    // Removing the focus-notification whitelist installs SystemUI hooks; enabling needs a
    // SystemUI restart. Callbacks read the per-app `<pkg>_focus` pref live, so disabling applies
    // immediately once the hooks are installed.
    Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST to RestartScopeSelection(systemUi = true),
    // Unlocking the whitelist signature verification hooks com.xiaomi.xmsf; xmsf is a declared
    // required scope entry, so the toggle flows through the standard Home restart button.
    Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH to RestartScopeSelection(xmsf = true)
)

private val ALL_MANUAL_RESTART_SCOPES = TWEAK_RESTART_SCOPES.values.fold(RestartScopeSelection.Empty) { acc, scopes ->
    acc.merge(scopes)
}

private const val KEY_PENDING_RESTART_BOOT_TOKEN = "pending_restart_boot_token"
private const val KEY_DIRTY_TWEAK_KEYS = "dirty_tweak_keys"
private const val KEY_TWEAK_BASELINE_PREFIX = "tweak_baseline_"
private const val KEY_FIRST_RUN_TOKEN = "first_run_token"

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
        android.util.Log.i("HyperTweak", "MainActivity onCreate, intent=${intent?.action}")

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

        // ShortcutService rejects dynamic shortcuts while the launcher alias is disabled.
        // Do not call it at all in that state: some Android builds propagate the
        // service-side IllegalStateException across Binder despite local catches.
        val launcherAliasEnabled = runCatching {
            packageManager.getComponentEnabledSetting(
                ComponentName(this, "com.takekazex.hypertweak.MainActivityAlias")
            ) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }.getOrDefault(true)
        if (launcherAliasEnabled) {
            runCatching { com.takekazex.hypertweak.util.ShortcutUtils.updateShortcuts(this) }
        }

        window.isNavigationBarContrastEnforced = false

        setContent {
            // Theme settings states
            var themeMode by remember { mutableIntStateOf(Preferences.getInt(Preferences.KEY_THEME_MODE, 0)) }
            var useMonet by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_USE_MONET, false)) }
            var seedColorHex by remember {
                mutableIntStateOf(
                    Preferences.getInt(Preferences.KEY_SEED_COLOR, Preferences.DEFAULT_SEED_COLOR)
                )
            }
            var paletteStyle by remember { mutableIntStateOf(Preferences.getInt(Preferences.KEY_THEME_PALETTE_STYLE, 0)) }
            var pureBlackDarkTheme by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_PURE_BLACK_DARK_THEME, false)) }
            var useFloatingBottomBar by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_USE_FLOATING_BOTTOM_BAR, false)) }
            var floatingBarStyle by remember { mutableIntStateOf(Preferences.getInt(Preferences.KEY_FLOATING_BAR_STYLE, 0)) }
            var predictiveBackStyle by remember { mutableIntStateOf(Preferences.getInt(Preferences.KEY_PREDICTIVE_BACK_STYLE, 1)) }
            // HyperTweak: on OS4 the AOSP back gesture is hidden and force-disabled (the
            // predictive-back Shell pipeline is broken platform-side). Retire the preference
            // once per launch so the off state persists even though the switch is hidden.
            val backGestureDisabledOnOs4 = remember {
                if (PlatformLevel.isOs4 &&
                    Preferences.getBoolean(Preferences.KEY_MIUI_BACK_GESTURE_HOOK, false)
                ) {
                    Preferences.putBoolean(Preferences.KEY_MIUI_BACK_GESTURE_HOOK, false)
                }
                PlatformLevel.isOs4
            }
            var miuiBackGestureHook by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_MIUI_BACK_GESTURE_HOOK, false)) }
            var crossTaskWallpaperBackground by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_CROSS_TASK_WALLPAPER_BACKGROUND, false)) }
            var aospBackIndicator by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_BACK_HYPEROS_INDICATOR, false)) }
            var aospBackHaptics by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_BACK_HYPEROS_HAPTICS, false)) }
            var aospBackHapticsEnhanced by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_BACK_HYPEROS_HAPTICS_ENHANCED, false)) }
            var aospBackSlideAnimation by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_BACK_SLIDE_ANIMATION, false)) }
            // Detected once per launch; the hook processes read the cached value.
            val launcherMajor = remember { LauncherVersion.refresh(applicationContext) }
            val launcherSupportsBackRoute = remember(launcherMajor) { LauncherVersion.isSupported }
            var aospBackMiuiHomeHooks by remember {
                mutableStateOf(
                    Preferences.getBoolean(
                        Preferences.KEY_AOSP_BACK_MIUI_HOME_HOOKS,
                        launcherSupportsBackRoute
                    )
                )
            }
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
            var quickShareEnabled by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_QUICK_SHARE_ENABLED, false)) }
            var fullScreenTranslate by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_FULL_SCREEN_TRANSLATE, false)) }
            var askAboutScreen by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_ASK_ABOUT_SCREEN, false)) }
            var hideFingerprint by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_HIDE_FINGERPRINT, false)) }
            var hideLockscreenStatusBar by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false)) }
            var lockscreenFingerprintAvoid by remember {
                mutableIntStateOf(
                    Preferences.getInt(
                        Preferences.KEY_LOCKSCREEN_FINGERPRINT_AVOID,
                        Preferences.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT
                    )
                )
            }
            var showInSettings by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_SHOW_IN_SETTINGS, false)) }
            var hideGestureBar by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false)) }
            var gestureBarRaiseLayout by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, false)) }
            var gestureBarActionsEnabled by remember {
                mutableStateOf(
                    Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_ACTIONS_ENABLED, false) &&
                        GestureBarAction.actionsAvailable
                )
            }
            var powerButtonAction by remember {
                mutableIntStateOf(Preferences.powerButtonAction())
            }
            var powerButtonHaptic by remember {
                mutableStateOf(
                    Preferences.getBoolean(
                        Preferences.KEY_POWER_BUTTON_HAPTIC,
                        Preferences.DEFAULT_POWER_BUTTON_HAPTIC
                    )
                )
            }
            var gestureBarLongPressAction by remember {
                mutableIntStateOf(
                    Preferences.getInt(
                        Preferences.KEY_GESTURE_BAR_LONG_PRESS_ACTION,
                        GestureBarAction.DEFAULT_ASSISTANT.persistedId
                    )
                )
            }
            var gestureBarDoubleTapAction by remember {
                mutableIntStateOf(
                    Preferences.getInt(
                        Preferences.KEY_GESTURE_BAR_DOUBLE_TAP_ACTION,
                        GestureBarAction.CIRCLE_TO_SEARCH.persistedId
                    )
                )
            }
            var hideLauncherIcon by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_HIDE_LAUNCHER_ICON, false)) }
            var sliderShowPercentage by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)) }
            var sliderSamePercentageStyle by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)) }
            var ccEditEnabled by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_CC_EDIT_ENABLED, false)) }
            var paModelSpoofEnabled by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_PA_MODEL_SPOOF, false)) }
            var mediaCardHideAppIcon by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_MEDIA_CARD_HIDE_APP_ICON, false)) }
            var mediaCardHideDeviceSwitch by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, false)) }
            var lockscreenAllNotifications by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, false)) }
            var lockscreenKeepNotifications by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, false)) }
            var unlockPasskey by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_UNLOCK_PASSKEY, false)) }
            var disableSpatialAudio by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)) }
            var forceAdaptiveAnc by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_FORCE_ADAPTIVE_ANC, false)) }
            var fcmLiveEnabled by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_FCM_LIVE_ENABLED, false)) }
            var focusNotificationUnlockWhitelist by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, false)) }
            var xmsfUnlockFocusAuth by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH, false)) }
            var immediateMonetRefresh by remember {
                mutableStateOf(
                    Preferences.getBoolean(
                        Preferences.KEY_IMMEDIATE_MONET_REFRESH,
                        Preferences.DEFAULT_IMMEDIATE_MONET_REFRESH
                    )
                )
            }

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
                    val storedKeys = localPrefs
                        .getStringSet(KEY_DIRTY_TWEAK_KEYS, emptySet())
                        .orEmpty()
                    val restartKeys = storedKeys.intersect(TWEAK_RESTART_SCOPES.keys)
                    if (restartKeys != storedKeys) {
                        localPrefs.edit { putStringSet(KEY_DIRTY_TWEAK_KEYS, restartKeys) }
                    }
                    mutableStateOf(restartKeys)
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
                    Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR -> hideLockscreenStatusBar
                    Preferences.KEY_HIDE_GESTURE_BAR -> hideGestureBar
                    Preferences.KEY_MIUI_BACK_GESTURE_HOOK -> miuiBackGestureHook
                    Preferences.KEY_CROSS_TASK_WALLPAPER_BACKGROUND -> crossTaskWallpaperBackground
                    Preferences.KEY_AOSP_BACK_MIUI_HOME_HOOKS -> aospBackMiuiHomeHooks
                    Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT -> gestureBarRaiseLayout
                    Preferences.KEY_GESTURE_BAR_ACTIONS_ENABLED -> gestureBarActionsEnabled
                    Preferences.KEY_SLIDER_SHOW_PERCENTAGE -> sliderShowPercentage
                    Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE -> sliderSamePercentageStyle
                    Preferences.KEY_SHOW_IN_SETTINGS -> showInSettings
                    Preferences.KEY_UNLOCK_PASSKEY -> unlockPasskey
                    Preferences.KEY_DISABLE_SPATIAL_AUDIO -> disableSpatialAudio
                    Preferences.KEY_FORCE_ADAPTIVE_ANC -> forceAdaptiveAnc
                    Preferences.KEY_FCM_LIVE_ENABLED -> fcmLiveEnabled
                    Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST -> focusNotificationUnlockWhitelist
                    Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH -> xmsfUnlockFocusAuth
                    else -> Preferences.getBoolean(key, false)
                }
            }

            /** Int-valued counterpart of [currentTweakValue] for restart-scope tracked selectors. */
            fun currentTweakValueInt(key: String): Int {
                return when (key) {
                    Preferences.KEY_LOCKSCREEN_FINGERPRINT_AVOID -> lockscreenFingerprintAvoid
                    else -> Preferences.getInt(key, Preferences.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT)
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

            /**
             * Int-valued counterpart of [markTweaked] for selector tweaks (e.g. the lockscreen
             * fingerprint-avoidance mode). The baseline is stored as an Int in the same
             * `tweak_baseline_` slot, so it must never be mixed with a Boolean-typed key.
             */
            fun markTweakedInt(key: String, value: Int) {
                val baselineKey = "$KEY_TWEAK_BASELINE_PREFIX$key"
                val baseline = if (localPrefs.contains(baselineKey)) {
                    localPrefs.getInt(baselineKey, value)
                } else {
                    Preferences.getInt(key, value)
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
                    putInt(baselineKey, baseline)
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
                        if (key == Preferences.KEY_LOCKSCREEN_FINGERPRINT_AVOID) {
                            // Int-typed selector tweaks store an Int baseline; the Boolean
                            // branch below would write a Boolean into the same slot and crash
                            // the next getInt baseline read with a ClassCastException.
                            putInt("$KEY_TWEAK_BASELINE_PREFIX$key", currentTweakValueInt(key))
                        } else {
                            putBoolean("$KEY_TWEAK_BASELINE_PREFIX$key", currentTweakValue(key))
                        }
                    }
                    putStringSet(KEY_DIRTY_TWEAK_KEYS, nextDirtyKeys)
                    putStringSet(Preferences.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
                }
            }

            /**
             * Quick Share on CN GMS works through a phenotype override that only GMS's own
             * process can write. GMS is a declared required scope, so the toggle only flips the
             * preference and marks the tweak dirty: the standard Home restart button then
             * restarts Google Play services, and the hooker writes the `sharing_supports_latchsky`
             * row (or removes it) at package-ready.
             */
            fun handleQuickShareChange(checked: Boolean) {
                quickShareEnabled = checked
                Preferences.putBoolean(Preferences.KEY_QUICK_SHARE_ENABLED, checked)
                markTweaked(Preferences.KEY_QUICK_SHARE_ENABLED, checked)
            }

            /**
             * Removing the focus-notification whitelist installs SystemUI hooks (SystemUI is a
             * declared required scope), so the toggle flips the preference and marks the tweak
             * dirty: the standard Home restart button then restarts SystemUI. The hooker reads the
             * per-app `<pkg>_focus` preference live, so a user's explicit shade-menu off for a
             * given app is always respected and disabling applies without another restart once
             * the hooks are installed.
             */
            fun handleFocusNotificationUnlockWhitelistChange(checked: Boolean) {
                focusNotificationUnlockWhitelist = checked
                Preferences.putBoolean(Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, checked)
                markTweaked(Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, checked)
            }

            /**
             * Unlocking the focus-notification whitelist signature verification hooks
             * com.xiaomi.xmsf. xmsf is a declared required scope (see `scope.list` and
             * `ScopeManager`), so the toggle requests the scope on the first enable, then flips
             * the preference and restarts the app — the hooker reads the preference live, so after
             * the restart the hooks install (on) or no-op (off). Turning the feature off only
             * flips the preference and restarts xmsf; the scope itself is kept.
             */
            @SuppressLint("LocalContextGetResourceValueCall")
            fun handleXmsfUnlockFocusAuthChange(checked: Boolean) {
                xmsfUnlockFocusAuth = checked
                Preferences.putBoolean(Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH, checked)
                // Block until the daemon has the new value: xmsf is force-stopped right after, and
                // its onHook reads this preference — without the flush it can restart on a stale
                // false and install none of the hooks.
                Preferences.flush()
                markTweaked(Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH, checked)
                coroutineScope.launch {
                    val xmsf = setOf("com.xiaomi.xmsf")
                    if (checked) {
                        when (val result = ScopeManager.request(xmsf)) {
                            is ScopeManager.Result.Applied, ScopeManager.Result.NoChange -> {
                                RestartUtils.forceStopPackages(context, coroutineScope, xmsf)
                            }
                            is ScopeManager.Result.Rejected -> {
                                xmsfUnlockFocusAuth = false
                                Preferences.putBoolean(Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH, false)
                                markTweaked(Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH, false)
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.xmsf_unlock_focus_auth_scope_not_granted,
                                        result.missing.joinToString()
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is ScopeManager.Result.Failed -> Toast.makeText(
                                context,
                                context.getString(
                                    R.string.xmsf_unlock_focus_auth_scope_failed,
                                    result.message
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                            ScopeManager.Result.ServiceUnavailable -> Toast.makeText(
                                context,
                                context.getString(R.string.xmsf_unlock_focus_auth_scope_unavailable),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        RestartUtils.forceStopPackages(context, coroutineScope, xmsf)
                    }
                }
            }

            /**
             * Full-screen translate lives in the Google app process, which is a declared required
             * scope (see `scope.list` and `ScopeManager`), so the toggle requests the scope on the
             * first enable, then flips the preference and restarts the app. The hooker reads the
             * preference live, so after the restart the button appears (on) or the hooks stop
             * firing (off).
             */
            @SuppressLint("LocalContextGetResourceValueCall")
            fun handleFullScreenTranslateChange(checked: Boolean) {
                fullScreenTranslate = checked
                Preferences.putBoolean(Preferences.KEY_FULL_SCREEN_TRANSLATE, checked)
                // Block until the daemon has the new value: the Google app is force-stopped right
                // after, and its onHook reads this preference — without the flush it can restart
                // on a stale false and install none of the hooks.
                Preferences.flush()
                coroutineScope.launch {
                    val googleApp = setOf(GoogleAppLiveTranslateHooker.PACKAGE)
                    if (checked) {
                        when (val result = ScopeManager.request(googleApp)) {
                            is ScopeManager.Result.Applied, ScopeManager.Result.NoChange -> {
                                RestartUtils.forceStopPackages(context, coroutineScope, googleApp)
                            }
                            is ScopeManager.Result.Rejected -> {
                                fullScreenTranslate = false
                                Preferences.putBoolean(Preferences.KEY_FULL_SCREEN_TRANSLATE, false)
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.full_screen_translate_scope_not_granted,
                                        result.missing.joinToString()
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is ScopeManager.Result.Failed -> Toast.makeText(
                                context,
                                context.getString(R.string.full_screen_translate_scope_failed, result.message),
                                Toast.LENGTH_LONG
                            ).show()
                            ScopeManager.Result.ServiceUnavailable -> Toast.makeText(
                                context,
                                context.getString(R.string.full_screen_translate_scope_unavailable),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        // The Google app is a declared required scope, so turning the feature off
                        // only flips the preference (the hooks read it live and no-op on restart);
                        // the scope itself is kept.
                        RestartUtils.forceStopPackages(context, coroutineScope, googleApp)
                    }
                }
            }

            /**
             * "Ask about this screen" lives in the same Google app process and required scope as
             * full-screen translate, so the toggle mirrors it: request the scope on the first
             * enable, then flip the preference and restart the app. The hooker reads the
             * preference live, so after the restart the searchbox capability opens (on) or the
             * hooks stop firing (off).
             */
            @SuppressLint("LocalContextGetResourceValueCall")
            fun handleAskAboutScreenChange(checked: Boolean) {
                askAboutScreen = checked
                Preferences.putBoolean(Preferences.KEY_ASK_ABOUT_SCREEN, checked)
                // Block until the daemon has the new value: the Google app is force-stopped right
                // after, and its onHook reads this preference — without the flush it can restart
                // on a stale false and install none of the hooks.
                Preferences.flush()
                coroutineScope.launch {
                    val googleApp = setOf(GoogleAppLiveTranslateHooker.PACKAGE)
                    if (checked) {
                        when (val result = ScopeManager.request(googleApp)) {
                            is ScopeManager.Result.Applied, ScopeManager.Result.NoChange -> {
                                RestartUtils.forceStopPackages(context, coroutineScope, googleApp)
                            }
                            is ScopeManager.Result.Rejected -> {
                                askAboutScreen = false
                                Preferences.putBoolean(Preferences.KEY_ASK_ABOUT_SCREEN, false)
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.google_feature_scope_not_granted,
                                        result.missing.joinToString()
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is ScopeManager.Result.Failed -> Toast.makeText(
                                context,
                                context.getString(R.string.google_feature_scope_failed, result.message),
                                Toast.LENGTH_LONG
                            ).show()
                            ScopeManager.Result.ServiceUnavailable -> Toast.makeText(
                                context,
                                context.getString(R.string.google_feature_scope_unavailable),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        // The Google app is a declared required scope, so turning the feature off
                        // only flips the preference (the hooks read it live and no-op on restart);
                        // the scope itself is kept.
                        RestartUtils.forceStopPackages(context, coroutineScope, googleApp)
                    }
                }
            }

            /**
             * Smart-Assistant model spoof lives in the `com.miui.personalassistant` process, which is
             * a declared required scope but is not a restart-scope target (the hooker rewrites the
             * request fields on every call, so the spoofed values apply live). On enable the toggle
             * requests the scope (in case the user removed the entry) and restarts the assistant so any
             * newly-added hook installs; disabling only flips the preference and the scope is kept.
             */
            @SuppressLint("LocalContextGetResourceValueCall")
            fun handlePaModelSpoofChange(checked: Boolean) {
                paModelSpoofEnabled = checked
                Preferences.putBoolean(Preferences.KEY_PA_MODEL_SPOOF, checked)
                // Block until the daemon has the new value: the assistant is force-stopped right
                // after and reads the preference on the next request.
                Preferences.flush()
                coroutineScope.launch {
                    val assistant = setOf("com.miui.personalassistant")
                    if (checked) {
                        when (val result = ScopeManager.request(assistant)) {
                            is ScopeManager.Result.Applied, ScopeManager.Result.NoChange -> {
                                RestartUtils.forceStopPackages(context, coroutineScope, assistant)
                            }
                            is ScopeManager.Result.Rejected -> {
                                paModelSpoofEnabled = false
                                Preferences.putBoolean(Preferences.KEY_PA_MODEL_SPOOF, false)
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.pa_model_spoof_scope_not_granted,
                                        result.missing.joinToString()
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is ScopeManager.Result.Failed -> Toast.makeText(
                                context,
                                context.getString(R.string.pa_model_spoof_scope_failed, result.message),
                                Toast.LENGTH_LONG
                            ).show()
                            ScopeManager.Result.ServiceUnavailable -> Toast.makeText(
                                context,
                                context.getString(R.string.pa_model_spoof_scope_unavailable),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        // The assistant is a declared required scope, so turning the feature off
                        // only flips the preference (the hooker reads it live); the scope is kept.
                        RestartUtils.forceStopPackages(context, coroutineScope, assistant)
                    }
                }
            }

            LaunchedEffect(serviceConnected) {
                // The remote copy of the settings lives in the LSPosed daemon and survives a
                // module uninstall, so a reinstall would silently restore the old config.
                // The module's own data dir is wiped on uninstall: when it is fresh but the
                // remote copy still holds settings, discard the leftovers once. `last_known_
                // module_activated` is written on every launch by every recent version, so
                // its presence means this is an ordinary update, not a reinstall.
                fun ensureFreshInstallReset() {
                    if (localPrefs.contains(KEY_FIRST_RUN_TOKEN)) return
                    if (!Preferences.isInitialized) return
                    val freshDataDir = !localPrefs.contains("last_known_module_activated")
                    if (freshDataDir && Preferences.hasRemoteConfig()) {
                        Preferences.clearAllSettings()
                    }
                    localPrefs.edit { putBoolean(KEY_FIRST_RUN_TOKEN, true) }
                }

                fun reloadAllPreferences() {
                    themeMode = Preferences.getInt(Preferences.KEY_THEME_MODE, 0)
                    useMonet = Preferences.getBoolean(Preferences.KEY_USE_MONET, false)
                    seedColorHex = Preferences.getInt(
                        Preferences.KEY_SEED_COLOR,
                        Preferences.DEFAULT_SEED_COLOR
                    )
                    paletteStyle = Preferences.getInt(Preferences.KEY_THEME_PALETTE_STYLE, 0)
                    pureBlackDarkTheme = Preferences.getBoolean(
                        Preferences.KEY_PURE_BLACK_DARK_THEME,
                        false
                    )
                    useFloatingBottomBar = Preferences.getBoolean(Preferences.KEY_USE_FLOATING_BOTTOM_BAR, false)
                    floatingBarStyle = Preferences.getInt(Preferences.KEY_FLOATING_BAR_STYLE, 0)
                    predictiveBackStyle = Preferences.getInt(Preferences.KEY_PREDICTIVE_BACK_STYLE, 1)
                    predictiveBackFollowGesture = Preferences.getBoolean(Preferences.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, true)
                    crossTaskWallpaperBackground = Preferences.getBoolean(Preferences.KEY_CROSS_TASK_WALLPAPER_BACKGROUND, false)
                    aospBackIndicator = Preferences.getBoolean(Preferences.KEY_AOSP_BACK_HYPEROS_INDICATOR, false)
                    aospBackHaptics = Preferences.getBoolean(Preferences.KEY_AOSP_BACK_HYPEROS_HAPTICS, false)
                    aospBackHapticsEnhanced = Preferences.getBoolean(Preferences.KEY_AOSP_BACK_HYPEROS_HAPTICS_ENHANCED, false)
                    aospBackSlideAnimation = Preferences.getBoolean(Preferences.KEY_AOSP_BACK_SLIDE_ANIMATION, false)
                    aospBackMiuiHomeHooks = Preferences.getBoolean(
                        Preferences.KEY_AOSP_BACK_MIUI_HOME_HOOKS,
                        launcherSupportsBackRoute
                    )
                    allowLandscape = Preferences.getBoolean(Preferences.KEY_ALLOW_LANDSCAPE, false)
                    pageScale = Preferences.getFloat(Preferences.KEY_PAGE_SCALE, 1.0f)
                    appLanguage = Preferences.getInt(Preferences.KEY_LANGUAGE, 0)
                    aodFullscreen = Preferences.getBoolean(Preferences.KEY_AOD_FULLSCREEN, false)
                    removeGms = Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)
                    quickShareEnabled = Preferences.getBoolean(Preferences.KEY_QUICK_SHARE_ENABLED, false)
                    fullScreenTranslate = Preferences.getBoolean(Preferences.KEY_FULL_SCREEN_TRANSLATE, false)
                    askAboutScreen = Preferences.getBoolean(Preferences.KEY_ASK_ABOUT_SCREEN, false)
                    hideFingerprint = Preferences.getBoolean(Preferences.KEY_HIDE_FINGERPRINT, false)
                    hideLockscreenStatusBar = Preferences.getBoolean(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false)
                    lockscreenFingerprintAvoid = Preferences.getInt(
                        Preferences.KEY_LOCKSCREEN_FINGERPRINT_AVOID,
                        Preferences.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT
                    )
                    showInSettings = Preferences.getBoolean(Preferences.KEY_SHOW_IN_SETTINGS, false)
                    hideGestureBar = Preferences.getBoolean(Preferences.KEY_HIDE_GESTURE_BAR, false)
                    gestureBarRaiseLayout = Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, false)
                    gestureBarActionsEnabled = Preferences.getBoolean(Preferences.KEY_GESTURE_BAR_ACTIONS_ENABLED, false) &&
                        GestureBarAction.actionsAvailable
                    powerButtonAction = Preferences.powerButtonAction()
                    powerButtonHaptic = Preferences.getBoolean(
                        Preferences.KEY_POWER_BUTTON_HAPTIC,
                        Preferences.DEFAULT_POWER_BUTTON_HAPTIC
                    )
                    gestureBarLongPressAction = Preferences.getInt(
                        Preferences.KEY_GESTURE_BAR_LONG_PRESS_ACTION,
                        GestureBarAction.DEFAULT_ASSISTANT.persistedId
                    )
                    gestureBarDoubleTapAction = Preferences.getInt(
                        Preferences.KEY_GESTURE_BAR_DOUBLE_TAP_ACTION,
                        GestureBarAction.CIRCLE_TO_SEARCH.persistedId
                    )
                    hideLauncherIcon = Preferences.getBoolean(Preferences.KEY_HIDE_LAUNCHER_ICON, false)
                    sliderShowPercentage = Preferences.getBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, false)
                    sliderSamePercentageStyle = Preferences.getBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                    ccEditEnabled = Preferences.getBoolean(Preferences.KEY_CC_EDIT_ENABLED, false)
                    paModelSpoofEnabled = Preferences.getBoolean(Preferences.KEY_PA_MODEL_SPOOF, false)
                    mediaCardHideAppIcon = Preferences.getBoolean(Preferences.KEY_MEDIA_CARD_HIDE_APP_ICON, false)
                    mediaCardHideDeviceSwitch = Preferences.getBoolean(Preferences.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, false)
                    lockscreenAllNotifications = Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, false)
                    lockscreenKeepNotifications = Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, false)
                    unlockPasskey = Preferences.getBoolean(Preferences.KEY_UNLOCK_PASSKEY, false)
                    disableSpatialAudio = Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)
                    forceAdaptiveAnc = Preferences.getBoolean(Preferences.KEY_FORCE_ADAPTIVE_ANC, false)
                    fcmLiveEnabled = Preferences.getBoolean(Preferences.KEY_FCM_LIVE_ENABLED, false)
                    focusNotificationUnlockWhitelist = Preferences.getBoolean(Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, false)
                    xmsfUnlockFocusAuth = Preferences.getBoolean(Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH, false)
                    immediateMonetRefresh = Preferences.getBoolean(
                        Preferences.KEY_IMMEDIATE_MONET_REFRESH,
                        Preferences.DEFAULT_IMMEDIATE_MONET_REFRESH
                    )
                }

                if (isModuleActive()) {
                    moduleActive = true
                    localPrefs.edit { putBoolean("last_known_module_activated", true) }
                    ensureFreshInstallReset()
                    reloadAllPreferences()
                    XposedServiceManager.refreshHotReloadTargets()
                    return@LaunchedEffect
                }

                if (serviceConnected != null) {
                    moduleActive = true
                    localPrefs.edit { putBoolean("last_known_module_activated", true) }
                    ensureFreshInstallReset()
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
            val deviceAccent = rememberDeviceAccentColor()
            val resolvedSeedColorHex = if (seedColorHex == 0) deviceAccent else seedColorHex

            val pureBlackActive = pureBlackDarkTheme && isEffectivelyDark(themeMode, isDark)
            val controller = remember(themeMode, useMonet, resolvedSeedColorHex, paletteStyle, pureBlackActive, isDark) {
                MiuixSpec2025Adapter.createThemeController(themeMode, useMonet, resolvedSeedColorHex, paletteStyle, pureBlackActive)
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
                        Preferences.putInt(Preferences.KEY_THEME_MODE, mode)
                    },
                    useMonet = useMonet,
                    onUseMonetChange = { monet ->
                        useMonet = monet
                        Preferences.putBoolean(Preferences.KEY_USE_MONET, monet)
                    },
                    seedColorHex = seedColorHex,
                    onSeedColorChange = { color ->
                        seedColorHex = color
                        Preferences.putInt(Preferences.KEY_SEED_COLOR, color)
                    },
                    paletteStyle = paletteStyle,
                    onPaletteStyleChange = { style ->
                        paletteStyle = style
                        Preferences.putInt(Preferences.KEY_THEME_PALETTE_STYLE, style)
                    },
                    pureBlackDarkTheme = pureBlackDarkTheme,
                    onPureBlackDarkThemeChange = { enabled ->
                        pureBlackDarkTheme = enabled
                        Preferences.putBoolean(Preferences.KEY_PURE_BLACK_DARK_THEME, enabled)
                    },
                    useFloatingBottomBar = useFloatingBottomBar,
                    onUseFloatingBottomBarChange = { floating ->
                        useFloatingBottomBar = floating
                        Preferences.putBoolean(Preferences.KEY_USE_FLOATING_BOTTOM_BAR, floating)
                    },
                    floatingBarStyle = floatingBarStyle,
                    onFloatingBarStyleChange = { style ->
                        floatingBarStyle = style
                        Preferences.putInt(Preferences.KEY_FLOATING_BAR_STYLE, style)
                    },
                    predictiveBackStyle = predictiveBackStyle,
                    onPredictiveBackStyleChange = { style ->
                        predictiveBackStyle = style
                        Preferences.putInt(Preferences.KEY_PREDICTIVE_BACK_STYLE, style)
                    },
                    miuiBackGestureHook = miuiBackGestureHook,
                    onMiuiBackGestureHookChange = { enabled ->
                        markTweaked(Preferences.KEY_MIUI_BACK_GESTURE_HOOK, enabled)
                        miuiBackGestureHook = enabled
                        Preferences.putBoolean(Preferences.KEY_MIUI_BACK_GESTURE_HOOK, enabled)
                    },
                    crossTaskWallpaperBackground = crossTaskWallpaperBackground,
                    onCrossTaskWallpaperBackgroundChange = { enabled ->
                        markTweaked(Preferences.KEY_CROSS_TASK_WALLPAPER_BACKGROUND, enabled)
                        crossTaskWallpaperBackground = enabled
                        Preferences.putBoolean(Preferences.KEY_CROSS_TASK_WALLPAPER_BACKGROUND, enabled)
                    },
                    // Read at dispatch time by the SystemUI runtime, so no restart is needed.
                    aospBackIndicator = aospBackIndicator,
                    onAospBackIndicatorChange = { enabled ->
                        aospBackIndicator = enabled
                        Preferences.putBoolean(Preferences.KEY_AOSP_BACK_HYPEROS_INDICATOR, enabled)
                    },
                    aospBackHaptics = aospBackHaptics,
                    onAospBackHapticsChange = { enabled ->
                        aospBackHaptics = enabled
                        Preferences.putBoolean(Preferences.KEY_AOSP_BACK_HYPEROS_HAPTICS, enabled)
                    },
                    aospBackHapticsEnhanced = aospBackHapticsEnhanced,
                    onAospBackHapticsEnhancedChange = { enabled ->
                        aospBackHapticsEnhanced = enabled
                        Preferences.putBoolean(Preferences.KEY_AOSP_BACK_HYPEROS_HAPTICS_ENHANCED, enabled)
                    },
                    aospBackSlideAnimation = aospBackSlideAnimation,
                    onAospBackSlideAnimationChange = { enabled ->
                        aospBackSlideAnimation = enabled
                        Preferences.putBoolean(Preferences.KEY_AOSP_BACK_SLIDE_ANIMATION, enabled)
                    },
                    launcherMajor = launcherMajor,
                    launcherSupportsBackRoute = launcherSupportsBackRoute,
                    aospBackMiuiHomeHooks = aospBackMiuiHomeHooks,
                    onAospBackMiuiHomeHooksChange = { enabled ->
                        markTweaked(Preferences.KEY_AOSP_BACK_MIUI_HOME_HOOKS, enabled)
                        aospBackMiuiHomeHooks = enabled
                        Preferences.putBoolean(Preferences.KEY_AOSP_BACK_MIUI_HOME_HOOKS, enabled)
                        // Records that the choice is the user's, so the runtime stops
                        // following the launcher-version default.
                        Preferences.putBoolean(
                            Preferences.KEY_AOSP_BACK_MIUI_HOME_HOOKS_USER_SET, true
                        )
                    },
                    predictiveBackFollowGesture = predictiveBackFollowGesture,
                    onPredictiveBackFollowGestureChange = { follow ->
                        predictiveBackFollowGesture = follow
                        Preferences.putBoolean(Preferences.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, follow)
                    },
                    allowLandscape = allowLandscape,
                    onAllowLandscapeChange = { allowed ->
                        allowLandscape = allowed
                        Preferences.putBoolean(Preferences.KEY_ALLOW_LANDSCAPE, allowed)
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
                        Preferences.putBoolean(Preferences.KEY_AOD_FULLSCREEN, checked)
                    },
                    removeGms = removeGms,
                    onRemoveGmsChange = { checked ->
                        removeGms = checked
                        Preferences.putBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, checked)
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
                    hideFingerprint = hideFingerprint,
                    hideLockscreenStatusBar = hideLockscreenStatusBar,
                    onHideLockscreenStatusBarChange = { checked ->
                        markTweaked(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, checked)
                        hideLockscreenStatusBar = checked
                        Preferences.putBoolean(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, checked)
                    },
                    lockscreenFingerprintAvoid = lockscreenFingerprintAvoid,
                    onLockscreenFingerprintAvoidChange = { mode ->
                        markTweakedInt(Preferences.KEY_LOCKSCREEN_FINGERPRINT_AVOID, mode)
                        lockscreenFingerprintAvoid = mode
                        Preferences.putInt(Preferences.KEY_LOCKSCREEN_FINGERPRINT_AVOID, mode)
                    },
                    onHideFingerprintChange = { checked ->
                        markTweaked(Preferences.KEY_HIDE_FINGERPRINT, checked)
                        hideFingerprint = checked
                        Preferences.putBoolean(Preferences.KEY_HIDE_FINGERPRINT, checked)
                    },
                    hideGestureBar = hideGestureBar,
                    onHideGestureBarChange = { checked ->
                        markTweaked(Preferences.KEY_HIDE_GESTURE_BAR, checked)
                        hideGestureBar = checked
                        Preferences.putBoolean(Preferences.KEY_HIDE_GESTURE_BAR, checked)
                    },
                    gestureBarRaiseLayout = gestureBarRaiseLayout,
                    onGestureBarRaiseLayoutChange = { checked ->
                        markTweaked(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, checked)
                        gestureBarRaiseLayout = checked
                        Preferences.putBoolean(Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT, checked)
                    },
                    gestureBarActionsEnabled = gestureBarActionsEnabled,
                    onGestureBarActionsEnabledChange = { checked ->
                        markTweaked(Preferences.KEY_GESTURE_BAR_ACTIONS_ENABLED, checked)
                        gestureBarActionsEnabled = checked
                        Preferences.putBoolean(
                            Preferences.KEY_GESTURE_BAR_ACTIONS_ENABLED,
                            checked
                        )
                    },
                    powerButtonAction = powerButtonAction,
                    onPowerButtonActionChange = { action ->
                        // System-server hooks: no restart scope exists, so no markTweaked; the
                        // hookers read the action live at dispatch time, so switching actions (or
                        // off) applies immediately once the hooks are installed. Turning the
                        // feature on from disabled still needs a reboot for the hooks to install.
                        powerButtonAction = action
                        Preferences.setPowerButtonAction(action)
                    },
                    powerButtonHaptic = powerButtonHaptic,
                    onPowerButtonHapticChange = { checked ->
                        powerButtonHaptic = checked
                        Preferences.putBoolean(Preferences.KEY_POWER_BUTTON_HAPTIC, checked)
                    },
                    gestureBarLongPressAction = gestureBarLongPressAction,
                    onGestureBarLongPressActionChange = { action ->
                        gestureBarLongPressAction = action
                        Preferences.putInt(
                            Preferences.KEY_GESTURE_BAR_LONG_PRESS_ACTION,
                            action
                        )
                    },
                    gestureBarDoubleTapAction = gestureBarDoubleTapAction,
                    onGestureBarDoubleTapActionChange = { action ->
                        gestureBarDoubleTapAction = action
                        Preferences.putInt(
                            Preferences.KEY_GESTURE_BAR_DOUBLE_TAP_ACTION,
                            action
                        )
                    },
                    sliderShowPercentage = sliderShowPercentage,
                    onSliderShowPercentageChange = { checked ->
                        markTweaked(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, checked)
                        sliderShowPercentage = checked
                        Preferences.putBoolean(Preferences.KEY_SLIDER_SHOW_PERCENTAGE, checked)
                    },
                    sliderSamePercentageStyle = sliderSamePercentageStyle,
                    onSliderSamePercentageChange = { checked ->
                        markTweaked(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, checked)
                        sliderSamePercentageStyle = checked
                        Preferences.putBoolean(Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE, checked)
                    },
                    ccEditEnabled = ccEditEnabled,
                    paModelSpoofEnabled = paModelSpoofEnabled,
                    mediaCardHideAppIcon = mediaCardHideAppIcon,
                    onMediaCardHideAppIconChange = { checked ->
                        markTweaked(Preferences.KEY_MEDIA_CARD_HIDE_APP_ICON, checked)
                        mediaCardHideAppIcon = checked
                        Preferences.putBoolean(Preferences.KEY_MEDIA_CARD_HIDE_APP_ICON, checked)
                    },
                    mediaCardHideDeviceSwitch = mediaCardHideDeviceSwitch,
                    onMediaCardHideDeviceSwitchChange = { checked ->
                        markTweaked(Preferences.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, checked)
                        mediaCardHideDeviceSwitch = checked
                        Preferences.putBoolean(Preferences.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, checked)
                    },
                    lockscreenAllNotifications = lockscreenAllNotifications,
                    onLockscreenAllNotificationsChange = { checked ->
                        markTweaked(Preferences.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, checked)
                        lockscreenAllNotifications = checked
                        Preferences.putBoolean(Preferences.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, checked)
                    },
                    lockscreenKeepNotifications = lockscreenKeepNotifications,
                    onLockscreenKeepNotificationsChange = { checked ->
                        markTweaked(Preferences.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, checked)
                        lockscreenKeepNotifications = checked
                        Preferences.putBoolean(Preferences.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, checked)
                    },
                    onCcEditEnabledChange = { checked ->
                        markTweaked(Preferences.KEY_CC_EDIT_ENABLED, checked)
                        ccEditEnabled = checked
                        Preferences.putBoolean(Preferences.KEY_CC_EDIT_ENABLED, checked)
                    },
                    onPaModelSpoofEnabledChange = { checked ->
                        handlePaModelSpoofChange(checked)
                    },
                    showInSettings = showInSettings,
                    onShowInSettingsChange = { checked ->
                        markTweaked(Preferences.KEY_SHOW_IN_SETTINGS, checked)
                        showInSettings = checked
                        Preferences.putBoolean(Preferences.KEY_SHOW_IN_SETTINGS, checked)
                    },
                    hideLauncherIcon = hideLauncherIcon,
                    onHideLauncherIconChange = { checked ->
                        hideLauncherIcon = checked
                        // Component state changes touch PackageManager and must stay on the
                        // activity thread; only preference persistence is dispatched.
                        setLauncherIconVisible(this@MainActivity, !checked)
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(Preferences.KEY_HIDE_LAUNCHER_ICON, checked)
                        }
                    },
                    immediateMonetRefresh = immediateMonetRefresh,
                    onImmediateMonetRefreshChange = { enabled ->
                        immediateMonetRefresh = enabled
                        Preferences.putBoolean(Preferences.KEY_IMMEDIATE_MONET_REFRESH, enabled)
                    },
                    unlockPasskey = unlockPasskey,
                    onUnlockPasskeyChange = { checked ->
                        markTweaked(Preferences.KEY_UNLOCK_PASSKEY, checked)
                        unlockPasskey = checked
                        Preferences.putBoolean(Preferences.KEY_UNLOCK_PASSKEY, checked)
                    },
                    disableSpatialAudio = disableSpatialAudio,
                    onDisableSpatialAudioChange = { checked ->
                        markTweaked(Preferences.KEY_DISABLE_SPATIAL_AUDIO, checked)
                        disableSpatialAudio = checked
                        Preferences.putBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, checked)
                    },
                    forceAdaptiveAnc = forceAdaptiveAnc,
                    onForceAdaptiveAncChange = { checked ->
                        markTweaked(Preferences.KEY_FORCE_ADAPTIVE_ANC, checked)
                        forceAdaptiveAnc = checked
                        Preferences.putBoolean(Preferences.KEY_FORCE_ADAPTIVE_ANC, checked)
                    },
                    fcmLiveEnabled = fcmLiveEnabled,
                    onFcmLiveEnabledChange = { checked ->
                        markTweaked(Preferences.KEY_FCM_LIVE_ENABLED, checked)
                        fcmLiveEnabled = checked
                        Preferences.putBoolean(Preferences.KEY_FCM_LIVE_ENABLED, checked)
                    },
                    focusNotificationUnlockWhitelist = focusNotificationUnlockWhitelist,
                    onFocusNotificationUnlockWhitelistChange = { checked ->
                        handleFocusNotificationUnlockWhitelistChange(checked)
                    },
                    xmsfUnlockFocusAuth = xmsfUnlockFocusAuth,
                    onXmsfUnlockFocusAuthChange = { checked ->
                        handleXmsfUnlockFocusAuthChange(checked)
                    },
                    backdrop = backdrop,
                    pageScale = pageScale,
                    onPageScaleChange = { scale ->
                        pageScale = scale
                        Preferences.putFloat(Preferences.KEY_PAGE_SCALE, scale)
                    },
                    onViewSourceCode = {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, "https://github.com/takekazex/HyperTweak".toUri())
                            this@MainActivity.startActivity(intent)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    },
                    onClearAllSettings = {
                        Preferences.clearAllSettings()
                        // Recreate so every Compose state reloads from the now-default prefs.
                        this@MainActivity.recreate()
                    },
                    onRestartScope = { selection ->
                        // Push every queued setting to the daemon before the scoped processes die;
                        // their hookers read the daemon copy at (re)load time.
                        Preferences.flush()
                        RestartUtils.restartScope(this@MainActivity, coroutineScope, selection)
                        clearRestartedScopes(selection)
                    },
                    onHotReload = { restartAllScopes ->
                        XposedServiceManager.hotReloadStaleTargets { report ->
                            if (restartAllScopes && report.failedCount == 0) {
                                Preferences.flush()
                                RestartUtils.restartScope(this@MainActivity, coroutineScope, ALL_MANUAL_RESTART_SCOPES)
                                clearRestartedScopes(ALL_MANUAL_RESTART_SCOPES)
                            }
                        }
                    },
                    appLanguage = appLanguage,
                    onAppLanguageChange = { lang ->
                        appLanguage = lang
                        Preferences.putInt(Preferences.KEY_LANGUAGE, lang)
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
    runCatching {
        val state = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, "com.takekazex.hypertweak.MainActivityAlias"),
            state,
            PackageManager.DONT_KILL_APP
        )
    }
}
}
