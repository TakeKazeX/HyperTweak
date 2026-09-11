package com.takekazex.hypertweak

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
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
import com.takekazex.hypertweak.ui.navigation.HyperTweakNavContainer
import com.takekazex.hypertweak.ui.page.LocalRestartScopeHandled
import com.takekazex.hypertweak.ui.page.LocalRestartScopeRequest
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
    Preferences.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_DOWNLOADS)
    ),
    Preferences.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_DOWNLOADS_UI)
    ),
    Preferences.KEY_DOWNLOAD_HIDE_XL to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_DOWNLOADS_UI)
    ),
    Preferences.KEY_DOWNLOAD_ADD_NEW_BUTTON to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_DOWNLOADS_UI)
    ),
    Preferences.KEY_HIDE_FINGERPRINT to RestartScopeSelection(systemUi = true),
    Preferences.KEY_HIDE_FINGERPRINT_AOD to RestartScopeSelection(systemUi = true),
    Preferences.KEY_HIDE_FINGERPRINT_LOCKSCREEN to RestartScopeSelection(systemUi = true),
    Preferences.KEY_HIDE_FINGERPRINT_APP_AUTH to RestartScopeSelection(systemUi = true),
    Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR to RestartScopeSelection(systemUi = true),
    Preferences.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS to RestartScopeSelection(systemUi = true),
    Preferences.KEY_NOTIFICATION_MONET_TEXT_COLOR to RestartScopeSelection(systemUi = true),
    Preferences.KEY_NOTIFICATION_FONT_WEIGHT to RestartScopeSelection(systemUi = true),
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
    Preferences.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS to RestartScopeSelection(settings = true),
    Preferences.KEY_DISABLE_VIDEO_RINGBACK to RestartScopeSelection(
        additionalPackages = setOf(
            RestartScopeSelection.PACKAGE_PHONE,
            RestartScopeSelection.PACKAGE_XIAOMI_PHONE
        )
    ),
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
    Preferences.KEY_LBE_CLIPBOARD_TOAST to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_LBE_SECURITY)
    ),
    Preferences.KEY_MITRUST_DISABLE_RISK_MONITORING to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_TRUST_SERVICE)
    ),
    Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_GUARD_PROVIDER)
    ),
    Preferences.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_GUARD_PROVIDER)
    ),
    Preferences.KEY_MILINK_BLOCK_HPPLAY_FILES to RestartScopeSelection(milink = true),
    // The Quick Share phenotype override lives in Google Play services; GMS is a declared
    // required scope entry, so the toggle flows through the standard Home restart button.
    Preferences.KEY_QUICK_SHARE_ENABLED to RestartScopeSelection(gms = true),
    // Removing the Super Island notification whitelist installs SystemUI hooks; enabling needs a
    // SystemUI restart. Callbacks read the per-app `<pkg>_focus` pref live, so disabling applies
    // immediately once the hooks are installed.
    Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST to RestartScopeSelection(systemUi = true),
    // The media Super Island dropdown whitelist is a separate SystemUI gate and is independently
    // controllable from the normal Super Island notification whitelist.
    Preferences.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST to RestartScopeSelection(systemUi = true),
    // Unlocking the whitelist signature verification hooks com.xiaomi.xmsf; xmsf is a declared
    // required scope entry, so the toggle flows through the standard Home restart button.
    Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH to RestartScopeSelection(xmsf = true),
    // Google-side feature toggles restart the Google app only after the user confirms the
    // standard Home restart dialog.
    Preferences.KEY_FULL_SCREEN_TRANSLATE to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_GOOGLE_APP)
    ),
    Preferences.KEY_ASK_ABOUT_SCREEN to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_GOOGLE_APP)
    ),
)

private const val KEY_PENDING_RESTART_BOOT_TOKEN = "pending_restart_boot_token"
private const val KEY_DIRTY_TWEAK_KEYS = "dirty_tweak_keys"
private const val KEY_TWEAK_BASELINE_PREFIX = "tweak_baseline_"
private const val KEY_MANUAL_PENDING_RESTART_SCOPES = "manual_pending_restart_scopes"
private const val KEY_FIRST_RUN_TOKEN = "first_run_token"
private const val KEY_FINGERPRINT_SPLIT_BASELINE_MIGRATED = "fingerprint_split_baseline_migrated"

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
                        false
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
            var blockDownloadXlLogDir by remember {
                mutableStateOf(Preferences.getBoolean(Preferences.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, false))
            }
            var downloadAlwaysShowFullLink by remember {
                mutableStateOf(Preferences.getBoolean(Preferences.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK, false))
            }
            var downloadHideXl by remember {
                mutableStateOf(Preferences.getBoolean(Preferences.KEY_DOWNLOAD_HIDE_XL, false))
            }
            var downloadAddNewButton by remember {
                mutableStateOf(Preferences.getBoolean(Preferences.KEY_DOWNLOAD_ADD_NEW_BUTTON, false))
            }
            var quickShareEnabled by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_QUICK_SHARE_ENABLED, false)) }
            var fullScreenTranslate by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_FULL_SCREEN_TRANSLATE, false)) }
            var askAboutScreen by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_ASK_ABOUT_SCREEN, false)) }
            var hideFingerprintAod by remember { mutableStateOf(Preferences.hideFingerprintAodEnabled()) }
            var hideFingerprintLockscreen by remember { mutableStateOf(Preferences.hideFingerprintLockscreenEnabled()) }
            var hideFingerprintAppAuth by remember {
                mutableStateOf(Preferences.hideFingerprintAppAuthEnabled())
            }
            var hideLockscreenStatusBar by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false)) }
            var notificationHeaderClockSeconds by remember {
                mutableStateOf(
                    Preferences.getBoolean(Preferences.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS, false)
                )
            }
            var notificationMonetTextColor by remember {
                mutableStateOf(
                    Preferences.getBoolean(Preferences.KEY_NOTIFICATION_MONET_TEXT_COLOR, false)
                )
            }
            var notificationFontWeight by remember {
                mutableStateOf(
                    Preferences.getBoolean(Preferences.KEY_NOTIFICATION_FONT_WEIGHT, false)
                )
            }
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
             var unlockThirdPartyDarkMode by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_UNLOCK_THIRD_PARTY_DARK_MODE, false)) }
            var disableSpatialAudio by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_DISABLE_SPATIAL_AUDIO, false)) }
            var forceAdaptiveAnc by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_FORCE_ADAPTIVE_ANC, false)) }
            var fcmLiveEnabled by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_FCM_LIVE_ENABLED, false)) }
            var lbeClipboardToast by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_LBE_CLIPBOARD_TOAST, false)) }
            var disableMiTrustRiskMonitoring by remember {
                mutableStateOf(Preferences.getBoolean(Preferences.KEY_MITRUST_DISABLE_RISK_MONITORING, false))
            }
            var disableGuardEnvironmentCheck by remember {
                mutableStateOf(
                    Preferences.getBoolean(
                        Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK,
                        false
                    )
                )
            }
            var blockGuardUploadAppList by remember {
                mutableStateOf(
                    Preferences.getBoolean(
                        Preferences.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST,
                        false
                    )
                )
            }
            var blockMiLinkHpplayFiles by remember {
                mutableStateOf(Preferences.getBoolean(Preferences.KEY_MILINK_BLOCK_HPPLAY_FILES, false))
            }
            var focusNotificationUnlockWhitelist by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, false)) }
            var mediaSuperIslandUnlockWhitelist by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST, false)) }
            var xmsfUnlockFocusAuth by remember { mutableStateOf(Preferences.getBoolean(Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH, false)) }
            var showGoogleServicesInSettings by remember {
                mutableStateOf(
                    Preferences.getBoolean(
                        Preferences.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS,
                        false
                    )
                )
            }
            var disableVideoRingback by remember {
                mutableStateOf(
                    Preferences.getBoolean(Preferences.KEY_DISABLE_VIDEO_RINGBACK, false)
                )
            }
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
                        remove(KEY_MANUAL_PENDING_RESTART_SCOPES)
                        remove(KEY_DIRTY_TWEAK_KEYS)
                        TWEAK_RESTART_SCOPES.keys.forEach { remove("$KEY_TWEAK_BASELINE_PREFIX$it") }
                        putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
                    }
                    mutableStateOf(emptySet())
                }
            }
            var manualPendingRestartScopes by remember {
                val storedBootToken = localPrefs.getString(KEY_PENDING_RESTART_BOOT_TOKEN, null)
                if (storedBootToken == bootToken) {
                    mutableStateOf(
                        RestartScopeSelection.fromKeySet(
                            localPrefs.getStringSet(KEY_MANUAL_PENDING_RESTART_SCOPES, emptySet()).orEmpty()
                        )
                    )
                } else {
                    mutableStateOf(RestartScopeSelection.Empty)
                }
            }
            var pendingRestartScopes by remember {
                val storedBootToken = localPrefs.getString(KEY_PENDING_RESTART_BOOT_TOKEN, null)
                if (storedBootToken == bootToken) {
                    mutableStateOf(
                        restartScopesForDirtyTweaks(dirtyTweakKeys).merge(manualPendingRestartScopes)
                    )
                } else {
                    mutableStateOf(RestartScopeSelection.Empty)
                }
            }

            fun effectivePendingRestartScopes(dirtyKeys: Set<String>): RestartScopeSelection {
                return restartScopesForDirtyTweaks(dirtyKeys).merge(manualPendingRestartScopes)
            }

            fun updateDirtyTweakKeys(next: Set<String>) {
                val nextPendingScopes = effectivePendingRestartScopes(next)
                dirtyTweakKeys = next
                pendingRestartScopes = nextPendingScopes
                localPrefs.edit {
                    putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
                    putStringSet(KEY_DIRTY_TWEAK_KEYS, next)
                    putStringSet(Preferences.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
                    putStringSet(KEY_MANUAL_PENDING_RESTART_SCOPES, manualPendingRestartScopes.toKeySet())
                }
            }

            fun currentTweakValue(key: String): Boolean {
                return when (key) {
                    Preferences.KEY_AOD_FULLSCREEN -> aodFullscreen
                    Preferences.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR -> blockDownloadXlLogDir
                    Preferences.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK -> downloadAlwaysShowFullLink
                    Preferences.KEY_DOWNLOAD_HIDE_XL -> downloadHideXl
                    Preferences.KEY_DOWNLOAD_ADD_NEW_BUTTON -> downloadAddNewButton
                    Preferences.KEY_HIDE_FINGERPRINT_AOD -> hideFingerprintAod
                    Preferences.KEY_HIDE_FINGERPRINT_LOCKSCREEN -> hideFingerprintLockscreen
                    Preferences.KEY_HIDE_FINGERPRINT_APP_AUTH -> hideFingerprintAppAuth
                    Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR -> hideLockscreenStatusBar
                    Preferences.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS -> notificationHeaderClockSeconds
                    Preferences.KEY_NOTIFICATION_MONET_TEXT_COLOR -> notificationMonetTextColor
                    Preferences.KEY_NOTIFICATION_FONT_WEIGHT -> notificationFontWeight
                    Preferences.KEY_HIDE_GESTURE_BAR -> hideGestureBar
                    Preferences.KEY_MIUI_BACK_GESTURE_HOOK -> miuiBackGestureHook
                    Preferences.KEY_CROSS_TASK_WALLPAPER_BACKGROUND -> crossTaskWallpaperBackground
                    Preferences.KEY_AOSP_BACK_MIUI_HOME_HOOKS -> aospBackMiuiHomeHooks
                    Preferences.KEY_GESTURE_BAR_RAISE_LAYOUT -> gestureBarRaiseLayout
                    Preferences.KEY_GESTURE_BAR_ACTIONS_ENABLED -> gestureBarActionsEnabled
                    Preferences.KEY_SLIDER_SHOW_PERCENTAGE -> sliderShowPercentage
                    Preferences.KEY_SLIDER_SAME_PERCENTAGE_STYLE -> sliderSamePercentageStyle
                    Preferences.KEY_SHOW_IN_SETTINGS -> showInSettings
                    Preferences.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS -> showGoogleServicesInSettings
                    Preferences.KEY_DISABLE_VIDEO_RINGBACK -> disableVideoRingback
                    Preferences.KEY_UNLOCK_PASSKEY -> unlockPasskey
                    Preferences.KEY_DISABLE_SPATIAL_AUDIO -> disableSpatialAudio
                    Preferences.KEY_FORCE_ADAPTIVE_ANC -> forceAdaptiveAnc
                    Preferences.KEY_FCM_LIVE_ENABLED -> fcmLiveEnabled
                    Preferences.KEY_LBE_CLIPBOARD_TOAST -> lbeClipboardToast
                    Preferences.KEY_MITRUST_DISABLE_RISK_MONITORING -> disableMiTrustRiskMonitoring
                    Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK -> disableGuardEnvironmentCheck
                    Preferences.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST -> blockGuardUploadAppList
                    Preferences.KEY_MILINK_BLOCK_HPPLAY_FILES -> blockMiLinkHpplayFiles
                    Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST -> focusNotificationUnlockWhitelist
                    Preferences.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST -> mediaSuperIslandUnlockWhitelist
                    Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH -> xmsfUnlockFocusAuth
                    Preferences.KEY_FULL_SCREEN_TRANSLATE -> fullScreenTranslate
                    Preferences.KEY_ASK_ABOUT_SCREEN -> askAboutScreen
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

            fun markTweaked(key: String, value: Boolean, defaultValue: Boolean = false) {
                val baselineKey = "$KEY_TWEAK_BASELINE_PREFIX$key"
                val splitFingerprintBaselineNeedsMigration =
                    (key == Preferences.KEY_HIDE_FINGERPRINT_AOD ||
                        key == Preferences.KEY_HIDE_FINGERPRINT_LOCKSCREEN) &&
                        (!localPrefs.getBoolean(KEY_FINGERPRINT_SPLIT_BASELINE_MIGRATED, false) ||
                            !localPrefs.contains(baselineKey))
                val baseline = if (splitFingerprintBaselineNeedsMigration) {
                    // A previous build tracked the merged lockscreen+AOD switch in this baseline
                    // slot. Rebase each surface once on its effective current value before the
                    // two surfaces become independent, so changing only one still requests a
                    // restart (including when the value comes from the legacy aggregate key).
                    when (key) {
                        Preferences.KEY_HIDE_FINGERPRINT_AOD -> Preferences.hideFingerprintAodEnabled()
                        else -> Preferences.hideFingerprintLockscreenEnabled()
                    }
                } else if (localPrefs.contains(baselineKey)) {
                    localPrefs.getBoolean(baselineKey, value)
                } else {
                    when (key) {
                        Preferences.KEY_HIDE_FINGERPRINT_AOD -> Preferences.hideFingerprintAodEnabled()
                        Preferences.KEY_HIDE_FINGERPRINT_LOCKSCREEN -> Preferences.hideFingerprintLockscreenEnabled()
                        else -> Preferences.getBoolean(key, defaultValue)
                    }
                }
                val nextDirtyKeys = if (value == baseline) {
                    dirtyTweakKeys - key
                } else {
                    dirtyTweakKeys + key
                }
                val nextPendingScopes = if (value == baseline) {
                    effectivePendingRestartScopes(nextDirtyKeys)
                } else {
                    pendingRestartScopes.merge(TWEAK_RESTART_SCOPES[key] ?: RestartScopeSelection.Empty)
                }

                dirtyTweakKeys = nextDirtyKeys
                pendingRestartScopes = nextPendingScopes
                localPrefs.edit {
                    putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
                    putBoolean(baselineKey, baseline)
                    if (key == Preferences.KEY_HIDE_FINGERPRINT_AOD ||
                        key == Preferences.KEY_HIDE_FINGERPRINT_LOCKSCREEN) {
                        putBoolean(KEY_FINGERPRINT_SPLIT_BASELINE_MIGRATED, true)
                    }
                    putStringSet(KEY_DIRTY_TWEAK_KEYS, nextDirtyKeys)
                    putStringSet(Preferences.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
                    putStringSet(KEY_MANUAL_PENDING_RESTART_SCOPES, manualPendingRestartScopes.toKeySet())
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
                    effectivePendingRestartScopes(nextDirtyKeys)
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
                    putStringSet(KEY_MANUAL_PENDING_RESTART_SCOPES, manualPendingRestartScopes.toKeySet())
                }
            }

            fun clearRestartedScopes(scopes: RestartScopeSelection) {
                val nextPendingScopes = pendingRestartScopes.without(scopes)
                val nextManualPendingScopes = manualPendingRestartScopes.without(scopes)
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
                    putStringSet(KEY_MANUAL_PENDING_RESTART_SCOPES, nextManualPendingScopes.toKeySet())
                }
                manualPendingRestartScopes = nextManualPendingScopes
            }

            fun clearPendingRestartTracking() {
                dirtyTweakKeys = emptySet()
                pendingRestartScopes = RestartScopeSelection.Empty
                manualPendingRestartScopes = RestartScopeSelection.Empty
                localPrefs.edit {
                    remove(KEY_PENDING_RESTART_BOOT_TOKEN)
                    remove(Preferences.KEY_PENDING_RESTART_SCOPES)
                    remove(KEY_MANUAL_PENDING_RESTART_SCOPES)
                    remove(KEY_DIRTY_TWEAK_KEYS)
                    TWEAK_RESTART_SCOPES.keys.forEach { remove("$KEY_TWEAK_BASELINE_PREFIX$it") }
                }
            }

            /** Records a secondary-page change for the shared Home restart dialog. */
            fun requestRestartScopes(scopes: RestartScopeSelection) {
                if (scopes.isEmpty()) return
                val nextManualPendingScopes = manualPendingRestartScopes.merge(scopes)
                val nextPendingScopes = pendingRestartScopes.merge(scopes)
                manualPendingRestartScopes = nextManualPendingScopes
                pendingRestartScopes = nextPendingScopes
                localPrefs.edit {
                    putString(KEY_PENDING_RESTART_BOOT_TOKEN, bootToken)
                    putStringSet(
                        Preferences.KEY_PENDING_RESTART_SCOPES,
                        nextPendingScopes.toKeySet()
                    )
                    putStringSet(
                        KEY_MANUAL_PENDING_RESTART_SCOPES,
                        nextManualPendingScopes.toKeySet()
                    )
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
                markTweaked(Preferences.KEY_QUICK_SHARE_ENABLED, checked)
                Preferences.putBoolean(Preferences.KEY_QUICK_SHARE_ENABLED, checked)
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
                markTweaked(Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, checked)
                Preferences.putBoolean(Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, checked)
            }

            /**
             * Removing the media Super Island dropdown whitelist uses its own SystemUI hook and
             * `<pkg>_focusmedia` per-app state, so it can be enabled without changing the normal
             * Super Island notification gate.
             */
            fun handleMediaSuperIslandUnlockWhitelistChange(checked: Boolean) {
                mediaSuperIslandUnlockWhitelist = checked
                markTweaked(Preferences.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST, checked)
                Preferences.putBoolean(Preferences.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST, checked)
            }

            /** The toggle only changes the preference; scope membership is managed separately. */
            fun handleXmsfUnlockFocusAuthChange(checked: Boolean) {
                xmsfUnlockFocusAuth = checked
                markTweaked(Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH, checked)
                Preferences.putBoolean(Preferences.KEY_XMSF_UNLOCK_FOCUS_AUTH, checked)
            }

            /** The toggle only changes the preference; scope membership is managed separately. */
            fun handleFullScreenTranslateChange(checked: Boolean) {
                fullScreenTranslate = checked
                markTweaked(Preferences.KEY_FULL_SCREEN_TRANSLATE, checked)
                Preferences.putBoolean(Preferences.KEY_FULL_SCREEN_TRANSLATE, checked)
            }

            /** The toggle only changes the preference; scope membership is managed separately. */
            fun handleAskAboutScreenChange(checked: Boolean) {
                askAboutScreen = checked
                markTweaked(Preferences.KEY_ASK_ABOUT_SCREEN, checked)
                Preferences.putBoolean(Preferences.KEY_ASK_ABOUT_SCREEN, checked)
            }

            /** Model spoof is read on every assistant request, so no process restart is needed. */
            fun handlePaModelSpoofChange(checked: Boolean) {
                paModelSpoofEnabled = checked
                Preferences.putBoolean(Preferences.KEY_PA_MODEL_SPOOF, checked)
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
                        if (!Preferences.clearAllSettings()) return
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
                        false
                    )
                    allowLandscape = Preferences.getBoolean(Preferences.KEY_ALLOW_LANDSCAPE, false)
                    pageScale = Preferences.getFloat(Preferences.KEY_PAGE_SCALE, 1.0f)
                    appLanguage = Preferences.getInt(Preferences.KEY_LANGUAGE, 0)
                    aodFullscreen = Preferences.getBoolean(Preferences.KEY_AOD_FULLSCREEN, false)
                    removeGms = Preferences.getBoolean(Preferences.KEY_REMOVE_GMS_RESTRICTION, false)
                    blockDownloadXlLogDir = Preferences.getBoolean(Preferences.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, false)
                    downloadAlwaysShowFullLink = Preferences.getBoolean(
                        Preferences.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK,
                        false
                    )
                    downloadHideXl = Preferences.getBoolean(Preferences.KEY_DOWNLOAD_HIDE_XL, false)
                    downloadAddNewButton = Preferences.getBoolean(
                        Preferences.KEY_DOWNLOAD_ADD_NEW_BUTTON,
                        false
                    )
                    quickShareEnabled = Preferences.getBoolean(Preferences.KEY_QUICK_SHARE_ENABLED, false)
                    fullScreenTranslate = Preferences.getBoolean(Preferences.KEY_FULL_SCREEN_TRANSLATE, false)
                    askAboutScreen = Preferences.getBoolean(Preferences.KEY_ASK_ABOUT_SCREEN, false)
                    hideFingerprintAod = Preferences.hideFingerprintAodEnabled()
                    hideFingerprintLockscreen = Preferences.hideFingerprintLockscreenEnabled()
                    hideFingerprintAppAuth = Preferences.hideFingerprintAppAuthEnabled()
                    hideLockscreenStatusBar = Preferences.getBoolean(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false)
                    notificationHeaderClockSeconds = Preferences.getBoolean(
                        Preferences.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS,
                        false
                    )
                    notificationMonetTextColor = Preferences.getBoolean(
                        Preferences.KEY_NOTIFICATION_MONET_TEXT_COLOR,
                        false
                    )
                    notificationFontWeight = Preferences.getBoolean(
                        Preferences.KEY_NOTIFICATION_FONT_WEIGHT,
                        false
                    )
                    lockscreenFingerprintAvoid = Preferences.getInt(
                        Preferences.KEY_LOCKSCREEN_FINGERPRINT_AVOID,
                        Preferences.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT
                    )
                    showInSettings = Preferences.getBoolean(Preferences.KEY_SHOW_IN_SETTINGS, false)
                    showGoogleServicesInSettings = Preferences.getBoolean(
                        Preferences.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS,
                        false
                    )
                    disableVideoRingback = Preferences.getBoolean(
                        Preferences.KEY_DISABLE_VIDEO_RINGBACK,
                        false
                    )
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
                    lbeClipboardToast = Preferences.getBoolean(Preferences.KEY_LBE_CLIPBOARD_TOAST, false)
                    disableMiTrustRiskMonitoring = Preferences.getBoolean(
                        Preferences.KEY_MITRUST_DISABLE_RISK_MONITORING,
                        false
                    )
                    disableGuardEnvironmentCheck = Preferences.getBoolean(
                        Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK,
                        false
                    )
                    blockGuardUploadAppList = Preferences.getBoolean(
                        Preferences.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST,
                        false
                    )
                    blockMiLinkHpplayFiles = Preferences.getBoolean(
                        Preferences.KEY_MILINK_BLOCK_HPPLAY_FILES,
                        false
                    )
                    focusNotificationUnlockWhitelist = Preferences.getBoolean(Preferences.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, false)
                    mediaSuperIslandUnlockWhitelist = Preferences.getBoolean(Preferences.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST, false)
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
                        aospBackMiuiHomeHooks = enabled
                        markTweaked(
                            Preferences.KEY_AOSP_BACK_MIUI_HOME_HOOKS,
                            enabled,
                            defaultValue = false
                        )
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
                    blockDownloadXlLogDir = blockDownloadXlLogDir,
                    onBlockDownloadXlLogDirChange = { checked ->
                        markTweaked(Preferences.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, checked)
                        blockDownloadXlLogDir = checked
                        Preferences.putBoolean(Preferences.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, checked)
                    },
                    downloadAlwaysShowFullLink = downloadAlwaysShowFullLink,
                    onDownloadAlwaysShowFullLinkChange = { checked ->
                        markTweaked(Preferences.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK, checked)
                        downloadAlwaysShowFullLink = checked
                        Preferences.putBoolean(Preferences.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK, checked)
                    },
                    downloadHideXl = downloadHideXl,
                    onDownloadHideXlChange = { checked ->
                        markTweaked(Preferences.KEY_DOWNLOAD_HIDE_XL, checked)
                        downloadHideXl = checked
                        Preferences.putBoolean(Preferences.KEY_DOWNLOAD_HIDE_XL, checked)
                    },
                    downloadAddNewButton = downloadAddNewButton,
                    onDownloadAddNewButtonChange = { checked ->
                        markTweaked(Preferences.KEY_DOWNLOAD_ADD_NEW_BUTTON, checked)
                        downloadAddNewButton = checked
                        Preferences.putBoolean(Preferences.KEY_DOWNLOAD_ADD_NEW_BUTTON, checked)
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
                    hideFingerprintAod = hideFingerprintAod,
                    onHideFingerprintAodChange = { checked ->
                        markTweaked(Preferences.KEY_HIDE_FINGERPRINT_AOD, checked)
                        hideFingerprintAod = checked
                        Preferences.putBoolean(Preferences.KEY_HIDE_FINGERPRINT_AOD, checked)
                    },
                    hideFingerprintLockscreen = hideFingerprintLockscreen,
                    onHideFingerprintLockscreenChange = { checked ->
                        markTweaked(Preferences.KEY_HIDE_FINGERPRINT_LOCKSCREEN, checked)
                        hideFingerprintLockscreen = checked
                        Preferences.putBoolean(Preferences.KEY_HIDE_FINGERPRINT_LOCKSCREEN, checked)
                    },
                    hideFingerprintAppAuth = hideFingerprintAppAuth,
                    onHideFingerprintAppAuthChange = { checked ->
                        markTweaked(
                            Preferences.KEY_HIDE_FINGERPRINT_APP_AUTH,
                            checked,
                            defaultValue = Preferences.getBoolean(Preferences.KEY_HIDE_FINGERPRINT, false)
                        )
                        hideFingerprintAppAuth = checked
                        Preferences.putBoolean(Preferences.KEY_HIDE_FINGERPRINT_APP_AUTH, checked)
                    },
                    hideLockscreenStatusBar = hideLockscreenStatusBar,
                    onHideLockscreenStatusBarChange = { checked ->
                        markTweaked(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, checked)
                        hideLockscreenStatusBar = checked
                        Preferences.putBoolean(Preferences.KEY_HIDE_LOCKSCREEN_STATUS_BAR, checked)
                    },
                    notificationHeaderClockSeconds = notificationHeaderClockSeconds,
                    onNotificationHeaderClockSecondsChange = { checked ->
                        markTweaked(Preferences.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS, checked)
                        notificationHeaderClockSeconds = checked
                        Preferences.putBoolean(Preferences.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS, checked)
                    },
                    notificationMonetTextColor = notificationMonetTextColor,
                    onNotificationMonetTextColorChange = { checked ->
                        markTweaked(Preferences.KEY_NOTIFICATION_MONET_TEXT_COLOR, checked)
                        notificationMonetTextColor = checked
                        Preferences.putBoolean(Preferences.KEY_NOTIFICATION_MONET_TEXT_COLOR, checked)
                    },
                    notificationFontWeight = notificationFontWeight,
                    onNotificationFontWeightChange = { checked ->
                        markTweaked(Preferences.KEY_NOTIFICATION_FONT_WEIGHT, checked)
                        notificationFontWeight = checked
                        Preferences.putBoolean(Preferences.KEY_NOTIFICATION_FONT_WEIGHT, checked)
                    },
                    lockscreenFingerprintAvoid = lockscreenFingerprintAvoid,
                    onLockscreenFingerprintAvoidChange = { mode ->
                        markTweakedInt(Preferences.KEY_LOCKSCREEN_FINGERPRINT_AVOID, mode)
                        lockscreenFingerprintAvoid = mode
                        Preferences.putInt(Preferences.KEY_LOCKSCREEN_FINGERPRINT_AVOID, mode)
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
                    showGoogleServicesInSettings = showGoogleServicesInSettings,
                    onShowGoogleServicesInSettingsChange = { checked ->
                        markTweaked(Preferences.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS, checked)
                        showGoogleServicesInSettings = checked
                        Preferences.putBoolean(
                            Preferences.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS,
                            checked
                        )
                    },
                    disableVideoRingback = disableVideoRingback,
                    onDisableVideoRingbackChange = { checked ->
                        markTweaked(Preferences.KEY_DISABLE_VIDEO_RINGBACK, checked)
                        disableVideoRingback = checked
                        Preferences.putBoolean(Preferences.KEY_DISABLE_VIDEO_RINGBACK, checked)
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
                    unlockThirdPartyDarkMode = unlockThirdPartyDarkMode,
                     onUnlockThirdPartyDarkModeChange = { checked ->
                         unlockThirdPartyDarkMode = checked
                         Preferences.putBoolean(Preferences.KEY_UNLOCK_THIRD_PARTY_DARK_MODE, checked)
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
                    lbeClipboardToast = lbeClipboardToast,
                    onLbeClipboardToastChange = { checked ->
                        markTweaked(Preferences.KEY_LBE_CLIPBOARD_TOAST, checked)
                        lbeClipboardToast = checked
                        Preferences.putBoolean(Preferences.KEY_LBE_CLIPBOARD_TOAST, checked)
                    },
                    disableMiTrustRiskMonitoring = disableMiTrustRiskMonitoring,
                    onDisableMiTrustRiskMonitoringChange = { checked ->
                        markTweaked(Preferences.KEY_MITRUST_DISABLE_RISK_MONITORING, checked)
                        disableMiTrustRiskMonitoring = checked
                        Preferences.putBoolean(
                            Preferences.KEY_MITRUST_DISABLE_RISK_MONITORING,
                            checked
                        )
                    },
                    disableGuardEnvironmentCheck = disableGuardEnvironmentCheck,
                    onDisableGuardEnvironmentCheckChange = { checked ->
                        markTweaked(Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK, checked)
                        disableGuardEnvironmentCheck = checked
                        Preferences.putBoolean(
                            Preferences.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK,
                            checked
                        )
                    },
                    blockGuardUploadAppList = blockGuardUploadAppList,
                    onBlockGuardUploadAppListChange = { checked ->
                        markTweaked(Preferences.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST, checked)
                        blockGuardUploadAppList = checked
                        Preferences.putBoolean(
                            Preferences.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST,
                            checked
                        )
                    },
                    blockMiLinkHpplayFiles = blockMiLinkHpplayFiles,
                    onBlockMiLinkHpplayFilesChange = { checked ->
                        markTweaked(Preferences.KEY_MILINK_BLOCK_HPPLAY_FILES, checked)
                        blockMiLinkHpplayFiles = checked
                        Preferences.putBoolean(Preferences.KEY_MILINK_BLOCK_HPPLAY_FILES, checked)
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
                    onClearAllSettings = { restartAllScopes, restartHyperTweak ->
                        if (Preferences.clearAllSettings()) {
                            clearPendingRestartTracking()
                            val restartScopesJob = if (restartAllScopes) {
                                coroutineScope.launch {
                                    Preferences.flush()
                                    // Include every package currently enabled in LSPosed, including
                                    // dynamically requested targets that are not in the legacy
                                    // fixed selection fields.
                                    val allScopes = ScopeManager.restartableScope(this@MainActivity)
                                        ?.let(RestartScopeSelection::fromPackageSet)
                                        ?: RestartScopeSelection.fromPackageSet(
                                            ScopeManager.declaredRestartableScope(this@MainActivity)
                                        )
                                    RestartUtils
                                        .restartScope(this@MainActivity, coroutineScope, allScopes)
                                        .join()
                                }
                            } else {
                                null
                            }
                            if (restartHyperTweak) {
                                coroutineScope.launch {
                                    restartScopesJob?.join()
                                    // Recreate so every Compose state reloads from the now-default prefs.
                                    this@MainActivity.recreate()
                                }
                            }
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                R.string.settings_clear_all_failed,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    onSettingsRestored = {
                        clearPendingRestartTracking()
                        setLauncherIconVisible(
                            this@MainActivity,
                            !Preferences.getBoolean(Preferences.KEY_HIDE_LAUNCHER_ICON, false)
                        )
                        // Recreate so every Compose state reflects the restored configuration.
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
                                coroutineScope.launch {
                                    Preferences.flush()
                                    // Include every package currently enabled in LSPosed, including
                                    // dynamically requested IMEs and newer feature targets that are
                                    // not represented by the legacy fixed selection fields.
                                    val allScopes = ScopeManager.restartableScope(this@MainActivity)
                                        ?.let(RestartScopeSelection::fromPackageSet)
                                        ?: RestartScopeSelection.fromPackageSet(
                                            ScopeManager.declaredRestartableScope(this@MainActivity)
                                        )
                                    RestartUtils.restartScope(this@MainActivity, coroutineScope, allScopes)
                                    clearRestartedScopes(allScopes)
                                }
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
