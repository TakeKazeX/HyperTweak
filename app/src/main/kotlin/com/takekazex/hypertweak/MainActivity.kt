package com.takekazex.hypertweak

import com.takekazex.hypertweak.settings.contract.IconSettingsKeys

import com.takekazex.hypertweak.settings.contract.AodSettingsKeys

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import com.takekazex.hypertweak.settings.contract.AppSettingsKeys

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
import com.takekazex.hypertweak.ui.effect.LocalAppBlurMode
import com.takekazex.hypertweak.ui.effect.AppBlurMode
import com.takekazex.hypertweak.ui.navigation.HyperTweakNavContainer
import com.takekazex.hypertweak.ui.page.LocalRestartScopeHandled
import com.takekazex.hypertweak.ui.page.LocalRestartScopeRequest
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import com.takekazex.hypertweak.util.update.UpdateManager
import com.takekazex.hypertweak.util.update.UpdateRecoveryManager
import com.takekazex.hypertweak.ui.page.UpdateAvailablePrompt
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.takekazex.hypertweak.ui.theme.MiuixSpec2025Adapter
import com.takekazex.hypertweak.ui.theme.rememberDeviceAccentColor
import com.takekazex.hypertweak.ui.theme.isEffectivelyDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.takekazex.hypertweak.util.RestartUtils
import com.takekazex.hypertweak.util.ScopeManager
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.LocaleHelper
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext

internal fun getSystemAccentColor(context: Context): Int {
    return try {
        context.getColor(android.R.color.system_accent1_500)
    } catch (e: Throwable) {
        0xFF007AFF.toInt()
    }
}

private val TWEAK_RESTART_SCOPES = mapOf(
    IconSettingsKeys.KEY_ICON_DUO_ENABLED to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_EXPANDED to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_SIZE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_RING_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_WIFI_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_CELLULAR_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_TYPE_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_DOTS_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_AIRPLANE_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_BATTERY_SCALE to RestartScopeSelection(systemUi = true),
    IconSettingsKeys.KEY_ICON_DUO_PERCENT_SCALE to RestartScopeSelection(systemUi = true),
    PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_DOWNLOADS)
    ),
    PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_DOWNLOADS_UI)
    ),
    PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_DOWNLOADS_UI)
    ),
    PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_DOWNLOADS_UI)
    ),
    SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_DATE to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE to RestartScopeSelection(systemUi = true),
    // The four per-element dp values are Float-typed prefs; only the Boolean master switch is
    // tracked here (markTweaked stores Boolean baselines — Float keys would crash the baseline
    // read in clearRestartedScopes). Changing a slider takes effect on the next SystemUI restart,
    // which the master-switch restart dialog or the manual scope restart already covers.
    SystemUiSettingsKeys.KEY_CC_CORNER_ENABLED to RestartScopeSelection(systemUi = true),
    // The editor-cards hooks install at control-center plugin load; only enabling needs the
    // restart (callbacks read the switch live, so disabling applies immediately).
    SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED to RestartScopeSelection(systemUi = true),
    // Same for the element-size hooks; the individual size keys are read live on every bind.
    SystemUiSettingsKeys.KEY_CC_RESIZE_ENABLED to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_APP_ICON to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS to RestartScopeSelection(systemUi = true),
    SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS to RestartScopeSelection(systemUi = true),
    PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS to RestartScopeSelection(settings = true),
    PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS to RestartScopeSelection(settings = true),
    PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE to RestartScopeSelection(settings = true),
    PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK to RestartScopeSelection(
        additionalPackages = setOf(
            RestartScopeSelection.PACKAGE_PHONE,
            RestartScopeSelection.PACKAGE_XIAOMI_PHONE
        )
    ),
    PlatformSettingsKeys.KEY_UNLOCK_PASSKEY to RestartScopeSelection(
        settings = true,
        securityCenter = true,
        scanner = true
    ),
    PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO to RestartScopeSelection(
        settings = true,
        milink = true,
        bluetooth = true
    ),
    PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC to RestartScopeSelection(
        settings = true,
        milink = true,
        bluetooth = true
    ),
    PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED to RestartScopeSelection(powerkeeper = true),
    PlatformSettingsKeys.KEY_LBE_CLIPBOARD_TOAST to RestartScopeSelection(
        // Both packages ship the vendor clipboard-read overlay; Security Center is the one that
        // actually builds the one the user sees, so both processes must reload the hook.
        securityCenter = true,
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_LBE_SECURITY)
    ),
    PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_TRUST_SERVICE)
    ),
    PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_GUARD_PROVIDER)
    ),
    PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES to RestartScopeSelection(milink = true),
    // The Quick Share phenotype override lives in Google Play services; GMS is a declared
    // required scope entry, so the toggle flows through the standard Home restart button.
    PlatformSettingsKeys.KEY_QUICK_SHARE_ENABLED to RestartScopeSelection(gms = true),
    // Google-side feature toggles restart the Google app only after the user confirms the
    // standard Home restart dialog.
    PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_GOOGLE_APP)
    ),
    PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_GOOGLE_APP)
    ),
    // The theme rights-check hook installs into the Theme Manager process. The framework-side
    // re-validation hook lives in system_server, which the app cannot restart; the Home page's
    // hot reload (or a reboot) is what applies that half.
    PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME to RestartScopeSelection(
        additionalPackages = setOf(RestartScopeSelection.PACKAGE_THEME_MANAGER)
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

/** Reads the live LSPosed scope, falling back to the module declaration while the service binds. */
private suspend fun currentRestartableScopes(context: Context): RestartScopeSelection {
    return ScopeManager.restartableScope(context)
        ?.let(RestartScopeSelection::fromPackageSet)
        ?: RestartScopeSelection.fromPackageSet(ScopeManager.declaredRestartableScope(context))
}

class MainActivity : ComponentActivity() {

    /**
     * Owns the in-app update flow for the module's own process. Created in [onCreate] and torn down
     * in [onDestroy] so its coroutine scope cannot outlive the Activity.
     */
    private lateinit var updateManager: UpdateManager

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

        // The update flow is owned by this Activity and runs only in the module's own process; no
        // hook process constructs it. Reconciliation runs off the main thread below because it
        // queries the package manager and touches the filesystem.
        updateManager = UpdateManager(this)

        setContent { AppContent() }
    }

    @Composable
    private fun AppContent() {
            var appLanguage by remember { mutableIntStateOf(Preferences.getInt(AppSettingsKeys.KEY_LANGUAGE, 0)) }
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val localizedContext = remember(context, appLanguage, configuration) {
                LocaleHelper.getLocalizedContext(context, appLanguage)
            }
            val noticeContext by rememberUpdatedState(localizedContext)
            val snackbarHostState = remember { SnackbarHostState() }

            // Package replacement kills the installing process, so the outcome of an update is
            // reconciled on the next launch: confirm the installed package really is the armed
            // target, delete the leftover download, and surface the one-shot completion notice.
            LaunchedEffect(Unit) {
                val notice = withContext(Dispatchers.IO) {
                    val reconciled = runCatching {
                        UpdateRecoveryManager(applicationContext).reconcile()
                    }.getOrNull()
                    // Restore the last known result before any network work, so opening the app
                    // shows a real answer without having to wait for (or be due for) a fresh query.
                    updateManager.primeFromCache()
                    reconciled
                }
                // Frequency-throttled and a no-op when the interval is "never", when offline, or
                // when this build is not the official package. Started before the snackbar below,
                // because `showSnackbar` suspends until the notice is dismissed and the background
                // check must not queue behind a completion message.
                updateManager.startAutoCheck()
                if (notice != null) {
                    val message = if (notice.versionCode != null) {
                        noticeContext.getString(R.string.update_completion_snackbar, notice.versionName, notice.versionCode)
                    } else {
                        noticeContext.getString(R.string.update_completion_snackbar_legacy, notice.versionName)
                    }
                    snackbarHostState.showSnackbar(message)
                }
            }

            // Theme settings states
            var appBlurMode by remember { mutableIntStateOf(Preferences.getInt(AppSettingsKeys.KEY_APP_BLUR_MODE, 1).coerceIn(0, 2)) }
            var themeMode by remember { mutableIntStateOf(Preferences.getInt(AppSettingsKeys.KEY_THEME_MODE, 0)) }
            var useMonet by remember { mutableStateOf(Preferences.getBoolean(AppSettingsKeys.KEY_USE_MONET, false)) }
            var seedColorHex by remember {
                mutableIntStateOf(
                    Preferences.getInt(AppSettingsKeys.KEY_SEED_COLOR, AppSettingsKeys.DEFAULT_SEED_COLOR)
                )
            }
            var paletteStyle by remember { mutableIntStateOf(Preferences.getInt(AppSettingsKeys.KEY_THEME_PALETTE_STYLE, 0)) }
            var pureBlackDarkTheme by remember { mutableStateOf(Preferences.getBoolean(AppSettingsKeys.KEY_PURE_BLACK_DARK_THEME, false)) }
            var useFloatingBottomBar by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_USE_FLOATING_BOTTOM_BAR, false)) }
            var floatingBarStyle by remember { mutableIntStateOf(Preferences.getInt(PlatformSettingsKeys.KEY_FLOATING_BAR_STYLE, 0)) }
            var predictiveBackStyle by remember { mutableIntStateOf(Preferences.getInt(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_STYLE, 1)) }
            var predictiveBackFollowGesture by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, true)) }
            var allowLandscape by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_ALLOW_LANDSCAPE, false)) }
            var pageScale by remember { mutableFloatStateOf(Preferences.getFloat(AppSettingsKeys.KEY_PAGE_SCALE, 1.0f)) }

            val serviceConnected by XposedServiceManager.serviceFlow.collectAsState()
            val staleTargets by XposedServiceManager.staleTargetsFlow.collectAsState()
            val hotReloading by XposedServiceManager.hotReloadingFlow.collectAsState()
            val hotReloadReport by XposedServiceManager.hotReloadReportFlow.collectAsState()

            // State variables for toggles
            var aodFullscreen by remember { mutableStateOf(Preferences.getBoolean(AodSettingsKeys.KEY_AOD_FULLSCREEN, false)) }
            var removeGms by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_REMOVE_GMS_RESTRICTION, false)) }
            var blockDownloadXlLogDir by remember {
                mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, false))
            }
            var downloadAlwaysShowFullLink by remember {
                mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK, false))
            }
            var downloadHideXl by remember {
                mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL, false))
            }
            var downloadAddNewButton by remember {
                mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON, false))
            }
            var quickShareEnabled by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_QUICK_SHARE_ENABLED, false)) }
            var fullScreenTranslate by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE, false)) }
            var askAboutScreen by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN, false)) }
            var hideFingerprintAod by remember { mutableStateOf(Preferences.hideFingerprintAodEnabled()) }
            var hideFingerprintLockscreen by remember { mutableStateOf(Preferences.hideFingerprintLockscreenEnabled()) }
            var hideFingerprintAppAuth by remember {
                mutableStateOf(Preferences.hideFingerprintAppAuthEnabled())
            }
            var hideLockscreenStatusBar by remember { mutableStateOf(Preferences.getBoolean(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false)) }
            var notificationHeaderClockSeconds by remember {
                mutableStateOf(
                    Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS, false)
                )
            }
            var notificationMonetTextColor by remember {
                mutableStateOf(
                    Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR, false)
                )
            }
            var notificationAbsoluteTime by remember {
                mutableStateOf(Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME, false))
            }
            var notificationFontWeight by remember {
                mutableStateOf(
                    Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT, false)
                )
            }
            var lockscreenFingerprintAvoid by remember {
                mutableIntStateOf(
                    Preferences.getInt(
                        SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID,
                        PlatformSettingsKeys.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT
                    )
                )
            }
            var showInSettings by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS, false)) }
            var hideGestureBar by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR, false)) }
            var gestureBarRaiseLayout by remember { mutableStateOf(Preferences.getBoolean(SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT, false)) }
            var hideGestureBarRecents by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_RECENTS, false)) }
            var hideGestureBarLauncher by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_LAUNCHER, false)) }
            var gestureBarAttachTransition by remember { mutableStateOf(Preferences.getBoolean(SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION, false)) }
            var powerButtonAction by remember {
                mutableIntStateOf(Preferences.powerButtonAction())
            }
            var contextualSearchHaptics by remember {
                mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_HAPTICS, false))
            }
            var contextualSearchLongPress by remember {
                mutableStateOf(Preferences.contextualSearchLongPress())
            }
            var hideLauncherIcon by remember { mutableStateOf(Preferences.getBoolean(AppSettingsKeys.KEY_HIDE_LAUNCHER_ICON, false)) }
            var sliderShowPercentage by remember { mutableStateOf(Preferences.getBoolean(SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE, false)) }
            var sliderSamePercentageStyle by remember { mutableStateOf(Preferences.getBoolean(SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)) }
            var ccEditEnabled by remember { mutableStateOf(Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED, false)) }
            var paModelSpoofEnabled by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF, false)) }
            var mediaCardHideAppIcon by remember { mutableStateOf(Preferences.getBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_APP_ICON, false)) }
            var mediaCardHideDeviceSwitch by remember { mutableStateOf(Preferences.getBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, false)) }
            var lockscreenAllNotifications by remember { mutableStateOf(Preferences.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, false)) }
            var lockscreenKeepNotifications by remember { mutableStateOf(Preferences.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, false)) }
            var unlockPasskey by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_UNLOCK_PASSKEY, false)) }
             var unlockThirdPartyDarkMode by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_UNLOCK_THIRD_PARTY_DARK_MODE, false)) }
            var disableSpatialAudio by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO, false)) }
            var forceAdaptiveAnc by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC, false)) }
            var fcmLiveEnabled by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED, false)) }
            var lbeClipboardToast by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_LBE_CLIPBOARD_TOAST, false)) }
            var disableMiTrustRiskMonitoring by remember {
                mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING, false))
            }
            var disableGuardEnvironmentCheck by remember {
                mutableStateOf(
                    Preferences.getBoolean(
                        PlatformSettingsKeys.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK,
                        false
                    )
                )
            }
            var blockGuardUploadAppList by remember {
                mutableStateOf(
                    Preferences.getBoolean(
                        PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST,
                        false
                    )
                )
            }
            var blockMiLinkHpplayFiles by remember {
                mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES, false))
            }
            var allowThirdPartyTheme by remember {
                mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME, false))
            }
            var removeIslandPlaceholder by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER, true)) }
            var threeIslands by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_THREE_ISLANDS, false)) }
            var landscapeFullIslands by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS, false)) }
            var landscapeMoreIslands by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS, false)) }
            var ccHideSuperIsland by remember { mutableStateOf(Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND, false)) }
            var islandLiveUpdateIcon by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON, false)) }
            var islandNarrowTimeFont by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_ISLAND_NARROW_TIME_FONT, false)) }
            var focusNotificationUnlockWhitelist by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, false)) }
            var mediaSuperIslandUnlockWhitelist by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST, false)) }
            var xmsfUnlockFocusAuth by remember { mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH, false)) }
            var showGoogleServicesInSettings by remember {
                mutableStateOf(
                    Preferences.getBoolean(
                        PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS,
                        false
                    )
                )
            }
            var settingsGlobalInterface by remember {
                mutableStateOf(Preferences.getBoolean(PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE, false))
            }
            var disableVideoRingback by remember {
                mutableStateOf(
                    Preferences.getBoolean(PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK, false)
                )
            }
            var immediateMonetRefresh by remember {
                mutableStateOf(
                    Preferences.getBoolean(
                        SystemUiSettingsKeys.KEY_IMMEDIATE_MONET_REFRESH,
                        SystemUiSettingsKeys.DEFAULT_IMMEDIATE_MONET_REFRESH
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

            val localPrefs = remember { getSharedPreferences(AppSettingsKeys.NAME, Context.MODE_PRIVATE) }
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
                        remove(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES)
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
                    putStringSet(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
                    putStringSet(KEY_MANUAL_PENDING_RESTART_SCOPES, manualPendingRestartScopes.toKeySet())
                }
            }

            fun currentTweakValue(key: String): Boolean {
                return when (key) {
                    AodSettingsKeys.KEY_AOD_FULLSCREEN -> aodFullscreen
                    PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR -> blockDownloadXlLogDir
                    PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK -> downloadAlwaysShowFullLink
                    PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL -> downloadHideXl
                    PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON -> downloadAddNewButton
                    SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD -> hideFingerprintAod
                    SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN -> hideFingerprintLockscreen
                    SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH -> hideFingerprintAppAuth
                    SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR -> hideLockscreenStatusBar
                    SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS -> notificationHeaderClockSeconds
                    SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR -> notificationMonetTextColor
                    SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT -> notificationFontWeight
                    SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME -> notificationAbsoluteTime
                    PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR -> hideGestureBar
                    SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT -> gestureBarRaiseLayout
                    SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE -> sliderShowPercentage
                    SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE -> sliderSamePercentageStyle
                    PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS -> showInSettings
                    PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS -> showGoogleServicesInSettings
                    PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE -> settingsGlobalInterface
                    PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK -> disableVideoRingback
                    PlatformSettingsKeys.KEY_UNLOCK_PASSKEY -> unlockPasskey
                    PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO -> disableSpatialAudio
                    PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC -> forceAdaptiveAnc
                    PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED -> fcmLiveEnabled
                    PlatformSettingsKeys.KEY_LBE_CLIPBOARD_TOAST -> lbeClipboardToast
                    PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING -> disableMiTrustRiskMonitoring
                    PlatformSettingsKeys.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK -> disableGuardEnvironmentCheck
                    PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST -> blockGuardUploadAppList
                    PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES -> blockMiLinkHpplayFiles
                    PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME -> allowThirdPartyTheme
                    PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER -> removeIslandPlaceholder
                    PlatformSettingsKeys.KEY_THREE_ISLANDS -> threeIslands
                    PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS -> landscapeFullIslands
                    PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS -> landscapeMoreIslands
                    SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND -> ccHideSuperIsland
                    PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON -> islandLiveUpdateIcon
                    PlatformSettingsKeys.KEY_ISLAND_NARROW_TIME_FONT -> islandNarrowTimeFont
                    PlatformSettingsKeys.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST -> focusNotificationUnlockWhitelist
                    PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST -> mediaSuperIslandUnlockWhitelist
                    PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH -> xmsfUnlockFocusAuth
                    PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE -> fullScreenTranslate
                    PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN -> askAboutScreen
                    else -> Preferences.getBoolean(key, false)
                }
            }

            /** Int-valued counterpart of [currentTweakValue] for restart-scope tracked selectors. */
            fun currentTweakValueInt(key: String): Int {
                return when (key) {
                    SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID -> lockscreenFingerprintAvoid
                    else -> Preferences.getInt(key, PlatformSettingsKeys.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT)
                }
            }

            fun markTweaked(key: String, value: Boolean, defaultValue: Boolean = false) {
                val baselineKey = "$KEY_TWEAK_BASELINE_PREFIX$key"
                val splitFingerprintBaselineNeedsMigration =
                    (key == SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD ||
                        key == SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN) &&
                        (!localPrefs.getBoolean(KEY_FINGERPRINT_SPLIT_BASELINE_MIGRATED, false) ||
                            !localPrefs.contains(baselineKey))
                val baseline = if (splitFingerprintBaselineNeedsMigration) {
                    // A previous build tracked the merged lockscreen+AOD switch in this baseline
                    // slot. Rebase each surface once on its effective current value before the
                    // two surfaces become independent, so changing only one still requests a
                    // restart (including when the value comes from the legacy aggregate key).
                    when (key) {
                        SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD -> Preferences.hideFingerprintAodEnabled()
                        else -> Preferences.hideFingerprintLockscreenEnabled()
                    }
                } else if (localPrefs.contains(baselineKey)) {
                    localPrefs.getBoolean(baselineKey, value)
                } else {
                    when (key) {
                        SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD -> Preferences.hideFingerprintAodEnabled()
                        SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN -> Preferences.hideFingerprintLockscreenEnabled()
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
                    if (key == SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD ||
                        key == SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN) {
                        putBoolean(KEY_FINGERPRINT_SPLIT_BASELINE_MIGRATED, true)
                    }
                    putStringSet(KEY_DIRTY_TWEAK_KEYS, nextDirtyKeys)
                    putStringSet(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
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
                    putStringSet(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
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
                        if (key == SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID) {
                            // Int-typed selector tweaks store an Int baseline; the Boolean
                            // branch below would write a Boolean into the same slot and crash
                            // the next getInt baseline read with a ClassCastException.
                            putInt("$KEY_TWEAK_BASELINE_PREFIX$key", currentTweakValueInt(key))
                        } else {
                            putBoolean("$KEY_TWEAK_BASELINE_PREFIX$key", currentTweakValue(key))
                        }
                    }
                    putStringSet(KEY_DIRTY_TWEAK_KEYS, nextDirtyKeys)
                    putStringSet(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES, nextPendingScopes.toKeySet())
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
                    remove(PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES)
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
                        PlatformSettingsKeys.KEY_PENDING_RESTART_SCOPES,
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
                markTweaked(PlatformSettingsKeys.KEY_QUICK_SHARE_ENABLED, checked)
                Preferences.putBoolean(PlatformSettingsKeys.KEY_QUICK_SHARE_ENABLED, checked)
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
                Preferences.putBoolean(PlatformSettingsKeys.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, checked)
            }

            /**
             * Removing the media Super Island dropdown whitelist uses its own SystemUI hook and
             * `<pkg>_focusmedia` per-app state, so it can be enabled without changing the normal
             * Super Island notification gate.
             */
            fun handleMediaSuperIslandUnlockWhitelistChange(checked: Boolean) {
                mediaSuperIslandUnlockWhitelist = checked
                markTweaked(PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST, checked)
                Preferences.putBoolean(PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST, checked)
            }

            /** The toggle only changes the preference; scope membership is managed separately. */
            fun handleXmsfUnlockFocusAuthChange(checked: Boolean) {
                xmsfUnlockFocusAuth = checked
                markTweaked(PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH, checked)
                Preferences.putBoolean(PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH, checked)
            }

            /** The toggle only changes the preference; scope membership is managed separately. */
            fun handleFullScreenTranslateChange(checked: Boolean) {
                fullScreenTranslate = checked
                markTweaked(PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE, checked)
                Preferences.putBoolean(PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE, checked)
            }

            /** The toggle only changes the preference; scope membership is managed separately. */
            fun handleAskAboutScreenChange(checked: Boolean) {
                askAboutScreen = checked
                markTweaked(PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN, checked)
                Preferences.putBoolean(PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN, checked)
            }

            /** Model spoof is read on every assistant request, so no process restart is needed. */
            fun handlePaModelSpoofChange(checked: Boolean) {
                paModelSpoofEnabled = checked
                Preferences.putBoolean(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF, checked)
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
                    appBlurMode = Preferences.getInt(AppSettingsKeys.KEY_APP_BLUR_MODE, 1).coerceIn(0, 2)
                    themeMode = Preferences.getInt(AppSettingsKeys.KEY_THEME_MODE, 0)
                    useMonet = Preferences.getBoolean(AppSettingsKeys.KEY_USE_MONET, false)
                    seedColorHex = Preferences.getInt(
                        AppSettingsKeys.KEY_SEED_COLOR,
                        AppSettingsKeys.DEFAULT_SEED_COLOR
                    )
                    paletteStyle = Preferences.getInt(AppSettingsKeys.KEY_THEME_PALETTE_STYLE, 0)
                    pureBlackDarkTheme = Preferences.getBoolean(
                        AppSettingsKeys.KEY_PURE_BLACK_DARK_THEME,
                        false
                    )
                    useFloatingBottomBar = Preferences.getBoolean(PlatformSettingsKeys.KEY_USE_FLOATING_BOTTOM_BAR, false)
                    floatingBarStyle = Preferences.getInt(PlatformSettingsKeys.KEY_FLOATING_BAR_STYLE, 0)
                    predictiveBackStyle = Preferences.getInt(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_STYLE, 1)
                    predictiveBackFollowGesture = Preferences.getBoolean(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, true)
                    allowLandscape = Preferences.getBoolean(PlatformSettingsKeys.KEY_ALLOW_LANDSCAPE, false)
                    pageScale = Preferences.getFloat(AppSettingsKeys.KEY_PAGE_SCALE, 1.0f)
                    appLanguage = Preferences.getInt(AppSettingsKeys.KEY_LANGUAGE, 0)
                    aodFullscreen = Preferences.getBoolean(AodSettingsKeys.KEY_AOD_FULLSCREEN, false)
                    removeGms = Preferences.getBoolean(PlatformSettingsKeys.KEY_REMOVE_GMS_RESTRICTION, false)
                    blockDownloadXlLogDir = Preferences.getBoolean(PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, false)
                    downloadAlwaysShowFullLink = Preferences.getBoolean(
                        PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK,
                        false
                    )
                    downloadHideXl = Preferences.getBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL, false)
                    downloadAddNewButton = Preferences.getBoolean(
                        PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON,
                        false
                    )
                    quickShareEnabled = Preferences.getBoolean(PlatformSettingsKeys.KEY_QUICK_SHARE_ENABLED, false)
                    fullScreenTranslate = Preferences.getBoolean(PlatformSettingsKeys.KEY_FULL_SCREEN_TRANSLATE, false)
                    askAboutScreen = Preferences.getBoolean(PlatformSettingsKeys.KEY_ASK_ABOUT_SCREEN, false)
                    hideFingerprintAod = Preferences.hideFingerprintAodEnabled()
                    hideFingerprintLockscreen = Preferences.hideFingerprintLockscreenEnabled()
                    hideFingerprintAppAuth = Preferences.hideFingerprintAppAuthEnabled()
                    hideLockscreenStatusBar = Preferences.getBoolean(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, false)
                    notificationHeaderClockSeconds = Preferences.getBoolean(
                        SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS,
                        false
                    )
                    notificationMonetTextColor = Preferences.getBoolean(
                        SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR,
                        false
                    )
                    notificationAbsoluteTime = Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME, false)
                    notificationFontWeight = Preferences.getBoolean(
                        SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT,
                        false
                    )
                    lockscreenFingerprintAvoid = Preferences.getInt(
                        SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID,
                        PlatformSettingsKeys.LOCKSCREEN_FINGERPRINT_AVOID_DEFAULT
                    )
                    showInSettings = Preferences.getBoolean(PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS, false)
                    showGoogleServicesInSettings = Preferences.getBoolean(
                        PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS,
                        false
                    )
                    settingsGlobalInterface = Preferences.getBoolean(
                        PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE,
                        false
                    )
                    disableVideoRingback = Preferences.getBoolean(
                        PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK,
                        false
                    )
                    hideGestureBar = Preferences.getBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR, false)
                    gestureBarRaiseLayout = Preferences.getBoolean(SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT, false)
                    hideGestureBarRecents = Preferences.getBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_RECENTS, false)
                    hideGestureBarLauncher = Preferences.getBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_LAUNCHER, false)
                    gestureBarAttachTransition = Preferences.getBoolean(SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION, false)
                    powerButtonAction = Preferences.powerButtonAction()
                    contextualSearchLongPress = Preferences.contextualSearchLongPress()
                    contextualSearchHaptics = Preferences.getBoolean(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_HAPTICS, false)
                    hideLauncherIcon = Preferences.getBoolean(AppSettingsKeys.KEY_HIDE_LAUNCHER_ICON, false)
                    sliderShowPercentage = Preferences.getBoolean(SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE, false)
                    sliderSamePercentageStyle = Preferences.getBoolean(SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE, false)
                    ccEditEnabled = Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED, false)
                    paModelSpoofEnabled = Preferences.getBoolean(PlatformSettingsKeys.KEY_PA_MODEL_SPOOF, false)
                    mediaCardHideAppIcon = Preferences.getBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_APP_ICON, false)
                    mediaCardHideDeviceSwitch = Preferences.getBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, false)
                    lockscreenAllNotifications = Preferences.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, false)
                    lockscreenKeepNotifications = Preferences.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, false)
                    unlockPasskey = Preferences.getBoolean(PlatformSettingsKeys.KEY_UNLOCK_PASSKEY, false)
                    disableSpatialAudio = Preferences.getBoolean(PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO, false)
                    forceAdaptiveAnc = Preferences.getBoolean(PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC, false)
                    fcmLiveEnabled = Preferences.getBoolean(PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED, false)
                    lbeClipboardToast = Preferences.getBoolean(PlatformSettingsKeys.KEY_LBE_CLIPBOARD_TOAST, false)
                    disableMiTrustRiskMonitoring = Preferences.getBoolean(
                        PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING,
                        false
                    )
                    disableGuardEnvironmentCheck = Preferences.getBoolean(
                        PlatformSettingsKeys.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK,
                        false
                    )
                    blockGuardUploadAppList = Preferences.getBoolean(
                        PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST,
                        false
                    )
                    blockMiLinkHpplayFiles = Preferences.getBoolean(
                        PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES,
                        false
                    )
                    allowThirdPartyTheme = Preferences.getBoolean(
                        PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME,
                        false
                    )
                    removeIslandPlaceholder = Preferences.getBoolean(PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER, true)
                    threeIslands = Preferences.getBoolean(PlatformSettingsKeys.KEY_THREE_ISLANDS, false)
                    landscapeFullIslands = Preferences.getBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS, false)
                    landscapeMoreIslands = Preferences.getBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS, false)
                    ccHideSuperIsland = Preferences.getBoolean(SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND, false)
                    islandLiveUpdateIcon = Preferences.getBoolean(PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON, false)
                    islandNarrowTimeFont = Preferences.getBoolean(PlatformSettingsKeys.KEY_ISLAND_NARROW_TIME_FONT, false)
                    focusNotificationUnlockWhitelist = Preferences.getBoolean(PlatformSettingsKeys.KEY_FOCUS_NOTIFICATION_UNLOCK_WHITELIST, false)
                    mediaSuperIslandUnlockWhitelist = Preferences.getBoolean(PlatformSettingsKeys.KEY_MEDIA_SUPER_ISLAND_UNLOCK_WHITELIST, false)
                    xmsfUnlockFocusAuth = Preferences.getBoolean(PlatformSettingsKeys.KEY_XMSF_UNLOCK_FOCUS_AUTH, false)
                    immediateMonetRefresh = Preferences.getBoolean(
                        SystemUiSettingsKeys.KEY_IMMEDIATE_MONET_REFRESH,
                        SystemUiSettingsKeys.DEFAULT_IMMEDIATE_MONET_REFRESH
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
            val controller = MiuixSpec2025Adapter.rememberThemeController(
                themeMode, useMonet, resolvedSeedColorHex, paletteStyle, pureBlackActive
            )

            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, pageScale) {
                Density(systemDensity.density * pageScale, systemDensity.fontScale)
            }

                    CompositionLocalProvider(
                        // A localized ConfigurationContext is not an Activity. Keep Activity-owned
                        // launchers and back dispatch available to navigation entries and windows.
                        LocalActivity provides this@MainActivity,
                        LocalActivityResultRegistryOwner provides this@MainActivity,
                        LocalOnBackPressedDispatcherOwner provides this@MainActivity,
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

                    HyperTweakNavContainer(
                    appBlurMode = appBlurMode,
                    onAppBlurModeChange = { mode ->
                        appBlurMode = mode.coerceIn(0, 2)
                        Preferences.putInt(AppSettingsKeys.KEY_APP_BLUR_MODE, appBlurMode)
                    },
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        Preferences.putInt(AppSettingsKeys.KEY_THEME_MODE, mode)
                    },
                    useMonet = useMonet,
                    onUseMonetChange = { monet ->
                        useMonet = monet
                        Preferences.putBoolean(AppSettingsKeys.KEY_USE_MONET, monet)
                    },
                    seedColorHex = seedColorHex,
                    onSeedColorChange = { color ->
                        seedColorHex = color
                        Preferences.putInt(AppSettingsKeys.KEY_SEED_COLOR, color)
                    },
                    paletteStyle = paletteStyle,
                    onPaletteStyleChange = { style ->
                        paletteStyle = style
                        Preferences.putInt(AppSettingsKeys.KEY_THEME_PALETTE_STYLE, style)
                    },
                    pureBlackDarkTheme = pureBlackDarkTheme,
                    onPureBlackDarkThemeChange = { enabled ->
                        pureBlackDarkTheme = enabled
                        Preferences.putBoolean(AppSettingsKeys.KEY_PURE_BLACK_DARK_THEME, enabled)
                    },
                    useFloatingBottomBar = useFloatingBottomBar,
                    onUseFloatingBottomBarChange = { floating ->
                        useFloatingBottomBar = floating
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_USE_FLOATING_BOTTOM_BAR, floating)
                    },
                    floatingBarStyle = floatingBarStyle,
                    onFloatingBarStyleChange = { style ->
                        floatingBarStyle = style
                        Preferences.putInt(PlatformSettingsKeys.KEY_FLOATING_BAR_STYLE, style)
                    },
                    predictiveBackStyle = predictiveBackStyle,
                    onPredictiveBackStyleChange = { style ->
                        predictiveBackStyle = style
                        Preferences.putInt(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_STYLE, style)
                    },
                    predictiveBackFollowGesture = predictiveBackFollowGesture,
                    onPredictiveBackFollowGestureChange = { follow ->
                        predictiveBackFollowGesture = follow
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_PREDICTIVE_BACK_FOLLOW_GESTURE, follow)
                    },
                    allowLandscape = allowLandscape,
                    onAllowLandscapeChange = { allowed ->
                        allowLandscape = allowed
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_ALLOW_LANDSCAPE, allowed)
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
                        Preferences.putBoolean(AodSettingsKeys.KEY_AOD_FULLSCREEN, checked)
                    },
                    blockDownloadXlLogDir = blockDownloadXlLogDir,
                    onBlockDownloadXlLogDirChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, checked)
                        blockDownloadXlLogDir = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_BLOCK_DOWNLOAD_XL_LOG_DIR, checked)
                    },
                    downloadAlwaysShowFullLink = downloadAlwaysShowFullLink,
                    onDownloadAlwaysShowFullLinkChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK, checked)
                        downloadAlwaysShowFullLink = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_ALWAYS_SHOW_FULL_LINK, checked)
                    },
                    downloadHideXl = downloadHideXl,
                    onDownloadHideXlChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL, checked)
                        downloadHideXl = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_HIDE_XL, checked)
                    },
                    downloadAddNewButton = downloadAddNewButton,
                    onDownloadAddNewButtonChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON, checked)
                        downloadAddNewButton = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_DOWNLOAD_ADD_NEW_BUTTON, checked)
                    },
                    // Google services and the experimental switches now live on their own
                    // second-level pages; the nav container consumes their state directly.
                    removeGms = removeGms,
                    onRemoveGmsChange = { checked ->
                        removeGms = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_REMOVE_GMS_RESTRICTION, checked)
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
                        Preferences.putBoolean(
                            PlatformSettingsKeys.KEY_SHOW_GOOGLE_SERVICES_IN_SETTINGS,
                            checked
                        )
                    },
                    settingsGlobalInterface = settingsGlobalInterface,
                    onSettingsGlobalInterfaceChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE, checked)
                        settingsGlobalInterface = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_SETTINGS_GLOBAL_INTERFACE, checked)
                    },
                    unlockPasskey = unlockPasskey,
                    onUnlockPasskeyChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_UNLOCK_PASSKEY, checked)
                        unlockPasskey = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_UNLOCK_PASSKEY, checked)
                    },
                    fcmLiveEnabled = fcmLiveEnabled,
                    onFcmLiveEnabledChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED, checked)
                        fcmLiveEnabled = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_FCM_LIVE_ENABLED, checked)
                    },
                    lbeClipboardToast = lbeClipboardToast,
                    onLbeClipboardToastChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_LBE_CLIPBOARD_TOAST, checked)
                        lbeClipboardToast = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_LBE_CLIPBOARD_TOAST, checked)
                    },
                    ccEditEnabled = ccEditEnabled,
                    onCcEditEnabledChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED, checked)
                        ccEditEnabled = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_CC_EDIT_ENABLED, checked)
                    },
                    hideFingerprintAod = hideFingerprintAod,
                    onHideFingerprintAodChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD, checked)
                        hideFingerprintAod = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_AOD, checked)
                    },
                    hideFingerprintLockscreen = hideFingerprintLockscreen,
                    onHideFingerprintLockscreenChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN, checked)
                        hideFingerprintLockscreen = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_LOCKSCREEN, checked)
                    },
                    hideFingerprintAppAuth = hideFingerprintAppAuth,
                    onHideFingerprintAppAuthChange = { checked ->
                        markTweaked(
                            SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH,
                            checked,
                            defaultValue = Preferences.getBoolean(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT, false)
                        )
                        hideFingerprintAppAuth = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_HIDE_FINGERPRINT_APP_AUTH, checked)
                    },
                    hideLockscreenStatusBar = hideLockscreenStatusBar,
                    onHideLockscreenStatusBarChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, checked)
                        hideLockscreenStatusBar = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_HIDE_LOCKSCREEN_STATUS_BAR, checked)
                    },
                    notificationHeaderClockSeconds = notificationHeaderClockSeconds,
                    onNotificationHeaderClockSecondsChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS, checked)
                        notificationHeaderClockSeconds = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS, checked)
                    },
                    notificationMonetTextColor = notificationMonetTextColor,
                    onNotificationMonetTextColorChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR, checked)
                        notificationMonetTextColor = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_MONET_TEXT_COLOR, checked)
                    },
                    notificationAbsoluteTime = notificationAbsoluteTime,
                    onNotificationAbsoluteTimeChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME, checked)
                        notificationAbsoluteTime = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_ABSOLUTE_TIME, checked)
                    },
                    notificationFontWeight = notificationFontWeight,
                    onNotificationFontWeightChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT, checked)
                        notificationFontWeight = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_FONT_WEIGHT, checked)
                    },
                    lockscreenFingerprintAvoid = lockscreenFingerprintAvoid,
                    onLockscreenFingerprintAvoidChange = { mode ->
                        markTweakedInt(SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID, mode)
                        lockscreenFingerprintAvoid = mode
                        Preferences.putInt(SystemUiSettingsKeys.KEY_LOCKSCREEN_FINGERPRINT_AVOID, mode)
                    },
                    hideGestureBar = hideGestureBar,
                    onHideGestureBarChange = { checked ->
                        hideGestureBar = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR, checked)
                    },
                    gestureBarRaiseLayout = gestureBarRaiseLayout,
                    onGestureBarRaiseLayoutChange = { checked ->
                        gestureBarRaiseLayout = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_GESTURE_BAR_RAISE_LAYOUT, checked)
                    },
                    hideGestureBarRecents = hideGestureBarRecents,
                    onHideGestureBarRecentsChange = { checked ->
                        hideGestureBarRecents = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_RECENTS, checked)
                    },
                    hideGestureBarLauncher = hideGestureBarLauncher,
                    onHideGestureBarLauncherChange = { checked ->
                        // SystemUI re-reads the top task from its own task-stack callbacks, so
                        // the switch applies on the next task move without a restart.
                        hideGestureBarLauncher = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_HIDE_GESTURE_BAR_LAUNCHER, checked)
                    },
                    gestureBarAttachTransition = gestureBarAttachTransition,
                    onGestureBarAttachTransitionChange = { checked ->
                        // Shared settings reach system_server; its native transition gate reads
                        // the accepted value on the next swipe, including after APK replacement.
                        gestureBarAttachTransition = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_GESTURE_BAR_ATTACH_TRANSITION, checked)
                    },
                    contextualSearchHaptics = contextualSearchHaptics,
                    onContextualSearchHapticsChange = { enabled ->
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_CONTEXTUAL_SEARCH_HAPTICS, enabled)
                        contextualSearchHaptics = enabled
                    },
                    contextualSearchLongPress = contextualSearchLongPress,
                    onContextualSearchLongPressChange = { enabled ->
                        contextualSearchLongPress = enabled
                        Preferences.putBoolean(
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
                        Preferences.setPowerButtonAction(action)
                    },
                    sliderShowPercentage = sliderShowPercentage,
                    onSliderShowPercentageChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE, checked)
                        sliderShowPercentage = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_SLIDER_SHOW_PERCENTAGE, checked)
                    },
                    sliderSamePercentageStyle = sliderSamePercentageStyle,
                    onSliderSamePercentageChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE, checked)
                        sliderSamePercentageStyle = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_SLIDER_SAME_PERCENTAGE_STYLE, checked)
                    },
                    paModelSpoofEnabled = paModelSpoofEnabled,
                    mediaCardHideAppIcon = mediaCardHideAppIcon,
                    onMediaCardHideAppIconChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_APP_ICON, checked)
                        mediaCardHideAppIcon = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_APP_ICON, checked)
                    },
                    mediaCardHideDeviceSwitch = mediaCardHideDeviceSwitch,
                    onMediaCardHideDeviceSwitchChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, checked)
                        mediaCardHideDeviceSwitch = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_MEDIA_CARD_HIDE_DEVICE_SWITCH, checked)
                    },
                    lockscreenAllNotifications = lockscreenAllNotifications,
                    onLockscreenAllNotificationsChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, checked)
                        lockscreenAllNotifications = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_ALL_NOTIFICATIONS, checked)
                    },
                    lockscreenKeepNotifications = lockscreenKeepNotifications,
                    onLockscreenKeepNotificationsChange = { checked ->
                        markTweaked(SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, checked)
                        lockscreenKeepNotifications = checked
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_KEEP_NOTIFICATIONS, checked)
                    },
                    onPaModelSpoofEnabledChange = { checked ->
                        handlePaModelSpoofChange(checked)
                    },
                    showInSettings = showInSettings,
                    onShowInSettingsChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS, checked)
                        showInSettings = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_SHOW_IN_SETTINGS, checked)
                    },
                    disableVideoRingback = disableVideoRingback,
                    onDisableVideoRingbackChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK, checked)
                        disableVideoRingback = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_DISABLE_VIDEO_RINGBACK, checked)
                    },
                    hideLauncherIcon = hideLauncherIcon,
                    onHideLauncherIconChange = { checked ->
                        hideLauncherIcon = checked
                        // Component state changes touch PackageManager and must stay on the
                        // activity thread; only preference persistence is dispatched.
                        setLauncherIconVisible(this@MainActivity, !checked)
                        coroutineScope.launch(Dispatchers.IO) {
                            Preferences.putBoolean(AppSettingsKeys.KEY_HIDE_LAUNCHER_ICON, checked)
                        }
                    },
                    immediateMonetRefresh = immediateMonetRefresh,
                    onImmediateMonetRefreshChange = { enabled ->
                        immediateMonetRefresh = enabled
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_IMMEDIATE_MONET_REFRESH, enabled)
                    },
                    unlockThirdPartyDarkMode = unlockThirdPartyDarkMode,
                     onUnlockThirdPartyDarkModeChange = { checked ->
                         unlockThirdPartyDarkMode = checked
                         Preferences.putBoolean(PlatformSettingsKeys.KEY_UNLOCK_THIRD_PARTY_DARK_MODE, checked)
                     },
                     disableSpatialAudio = disableSpatialAudio,
                    onDisableSpatialAudioChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO, checked)
                        disableSpatialAudio = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_DISABLE_SPATIAL_AUDIO, checked)
                    },
                    forceAdaptiveAnc = forceAdaptiveAnc,
                    onForceAdaptiveAncChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC, checked)
                        forceAdaptiveAnc = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_FORCE_ADAPTIVE_ANC, checked)
                    },
                    disableMiTrustRiskMonitoring = disableMiTrustRiskMonitoring,
                    onDisableMiTrustRiskMonitoringChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING, checked)
                        disableMiTrustRiskMonitoring = checked
                        Preferences.putBoolean(
                            PlatformSettingsKeys.KEY_MITRUST_DISABLE_RISK_MONITORING,
                            checked
                        )
                    },
                    disableGuardEnvironmentCheck = disableGuardEnvironmentCheck,
                    onDisableGuardEnvironmentCheckChange = { checked ->
                        disableGuardEnvironmentCheck = checked
                        Preferences.putBoolean(
                            PlatformSettingsKeys.KEY_GUARD_PROVIDER_DISABLE_ENVIRONMENT_CHECK,
                            checked
                        )
                    },
                    blockGuardUploadAppList = blockGuardUploadAppList,
                    onBlockGuardUploadAppListChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST, checked)
                        blockGuardUploadAppList = checked
                        Preferences.putBoolean(
                            PlatformSettingsKeys.KEY_GUARD_PROVIDER_BLOCK_UPLOAD_APP_LIST,
                            checked
                        )
                    },
                    blockMiLinkHpplayFiles = blockMiLinkHpplayFiles,
                    onBlockMiLinkHpplayFilesChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES, checked)
                        blockMiLinkHpplayFiles = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_MILINK_BLOCK_HPPLAY_FILES, checked)
                    },
                    allowThirdPartyTheme = allowThirdPartyTheme,
                    onAllowThirdPartyThemeChange = { checked ->
                        markTweaked(PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME, checked)
                        allowThirdPartyTheme = checked
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_ALLOW_THIRD_PARTY_THEME, checked)
                    },
                    removeIslandPlaceholder = removeIslandPlaceholder,
                    onRemoveIslandPlaceholderChange = { checked ->
                        removeIslandPlaceholder = checked
                        markTweaked(PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER, checked)
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_REMOVE_ISLAND_PLACEHOLDER, checked)
                    },
                    threeIslands = threeIslands,
                    landscapeFullIslands = landscapeFullIslands,
                    landscapeMoreIslands = landscapeMoreIslands,
                    onLandscapeMoreIslandsChange = { checked ->
                        landscapeMoreIslands = checked
                        markTweaked(PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS, checked)
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_MORE_ISLANDS, checked)
                    },
                    onLandscapeFullIslandsChange = { checked ->
                        landscapeFullIslands = checked
                        markTweaked(PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS, checked)
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_LANDSCAPE_FULL_ISLANDS, checked)
                    },
                    onThreeIslandsChange = { checked ->
                        threeIslands = checked
                        markTweaked(PlatformSettingsKeys.KEY_THREE_ISLANDS, checked)
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_THREE_ISLANDS, checked)
                    },
                    ccHideSuperIsland = ccHideSuperIsland,
                    islandLiveUpdateIcon = islandLiveUpdateIcon,
                    islandNarrowTimeFont = islandNarrowTimeFont,
                    onIslandLiveUpdateIconChange = { checked ->
                        islandLiveUpdateIcon = checked
                        markTweaked(PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON, checked)
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_ISLAND_LIVE_UPDATE_ICON, checked)
                    },
                    onIslandNarrowTimeFontChange = { checked ->
                        islandNarrowTimeFont = checked
                        markTweaked(PlatformSettingsKeys.KEY_ISLAND_NARROW_TIME_FONT, checked)
                        Preferences.putBoolean(PlatformSettingsKeys.KEY_ISLAND_NARROW_TIME_FONT, checked)
                    },
                    onCcHideSuperIslandChange = { checked ->
                        ccHideSuperIsland = checked
                        markTweaked(SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND, checked)
                        Preferences.putBoolean(SystemUiSettingsKeys.KEY_CC_HIDE_SUPER_ISLAND, checked)
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
                        Preferences.putFloat(AppSettingsKeys.KEY_PAGE_SCALE, scale)
                    },
                    onViewSourceCode = {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, "https://github.com/takekazex/HyperTweak".toUri())
                            this@MainActivity.startActivity(intent)
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
                            this@MainActivity.startActivity(intent)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    },
                    onClearAllSettings = { restartAllScopes, restartHyperTweak ->
                        // clearAllSettings() waits on the daemon-backed commit (up to 5 s via
                        // commitRemoteMutation), so it must not run on the main thread: invoking it
                        // inline from the dialog's onClick froze the UI long enough to ANR.
                        coroutineScope.launch {
                            val cleared = withContext(Dispatchers.IO) { Preferences.clearAllSettings() }
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
                                    Preferences.flush()
                                    val allScopes = currentRestartableScopes(this@MainActivity)
                                    RestartUtils
                                        .restartScope(this@MainActivity, coroutineScope, allScopes)
                                        .join()
                                }
                            } else {
                                null
                            }
                            if (restartHyperTweak) {
                                launch {
                                    restartScopesJob?.join()
                                    // Recreate so every Compose state reloads from the now-default prefs.
                                    this@MainActivity.recreate()
                                }
                            }
                        }
                    },
                    onSettingsRestored = {
                        clearPendingRestartTracking()
                        setLauncherIconVisible(
                            this@MainActivity,
                            !Preferences.getBoolean(AppSettingsKeys.KEY_HIDE_LAUNCHER_ICON, false)
                        )
                        // Recreate so every Compose state reflects the restored configuration.
                        this@MainActivity.recreate()
                    },
                    onRestartScope = { selection ->
                        // Push every queued setting to the daemon before the scoped processes die;
                        // their hookers read the daemon copy at (re)load time.
                        Preferences.flush()
                        RestartUtils.restartScope(this@MainActivity, coroutineScope, selection, ::clearRestartedScopes)
                    },
                    onHotReload = {
                        // Hot reload re-attaches the module to the already running stale targets;
                        // it must remain independent from the normal force-stop/restart path.
                        Preferences.flush()
                        XposedServiceManager.hotReloadStaleTargets()
                    },
                    onRestartAllScopes = {
                        coroutineScope.launch {
                            Preferences.flush()
                            val allScopes = currentRestartableScopes(this@MainActivity)
                            RestartUtils
                                .restartScope(this@MainActivity, coroutineScope, allScopes, ::clearRestartedScopes)
                                .join()
                            // Refresh after the restart completes so a previously stale warning
                            // disappears without requiring a second app launch.
                            XposedServiceManager.refreshHotReloadTargets()
                        }
                    },
                    appLanguage = appLanguage,
                    onAppLanguageChange = { lang ->
                        appLanguage = lang
                        Preferences.putInt(AppSettingsKeys.KEY_LANGUAGE, lang)
                    },
                    onShortcutsChanged = {
                        coroutineScope.launch(Dispatchers.IO) {
                            runCatching { com.takekazex.hypertweak.util.ShortcutUtils.updateShortcuts(this@MainActivity) }
                        }
                    },
                    updateManager = updateManager,
                    snackbarHostState = snackbarHostState
                    )

                    // Hosted here, not inside the update page: the silent check runs at launch, so a
                    // prompt owned by that page would only ever be seen after navigating to it.
                    UpdateAvailablePrompt(updateManager)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A package update can restart scoped targets without rebinding the service. Re-query when
        // returning to the app so the Home warning does not outlive the target's actual state.
        XposedServiceManager.refreshHotReloadTargets()
        // Also the recovery point for the "allow installs from this source" round trip: if the user
        // granted it while away, the pending install is re-dispatched here.
        if (::updateManager.isInitialized) updateManager.onResume()
    }

    override fun onDestroy() {
        if (::updateManager.isInitialized) updateManager.close()
        super.onDestroy()
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
