package com.takekazex.hypertweak.ui.app

import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.AppSettingsStore
import com.takekazex.hypertweak.ui.chrome.LocalAppUiStyle
import com.takekazex.hypertweak.ui.chrome.appUiStyleFromStored
import com.takekazex.hypertweak.ui.components.dialog.LocalRestartScopeHandled
import com.takekazex.hypertweak.ui.components.dialog.LocalRestartScopeRequest
import com.takekazex.hypertweak.ui.effect.AppBlurMode
import com.takekazex.hypertweak.ui.effect.LocalAppBlurMode
import com.takekazex.hypertweak.ui.navigation.HyperTweakNavContainer
import com.takekazex.hypertweak.ui.navigation.NavigationSettings
import com.takekazex.hypertweak.ui.page.update.UpdateAvailablePrompt
import com.takekazex.hypertweak.ui.state.DownloadXlCleanup
import com.takekazex.hypertweak.ui.state.LocalDownloadXlCleanup
import com.takekazex.hypertweak.ui.theme.MiuixSpec2025Adapter
import com.takekazex.hypertweak.ui.theme.isEffectivelyDark
import com.takekazex.hypertweak.ui.theme.rememberDeviceAccentColor
import com.takekazex.hypertweak.util.platform.AdbTcpPort
import com.takekazex.hypertweak.util.platform.LocaleHelper
import com.takekazex.hypertweak.util.update.UpdateManager
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
    val debugRevision by com.takekazex.hypertweak.util.diagnostics.DebugSettingsEvents.revision.collectAsStateWithLifecycle()
    LaunchedEffect(debugRevision) { if (debugRevision > 0) settings.reload() }
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

            val serviceConnected by services.module.connection.collectAsStateWithLifecycle()
            val staleTargets by services.module.staleProcessNames.collectAsStateWithLifecycle()
            val hotReloading by services.module.hotReloadingFlow.collectAsStateWithLifecycle()
            val hotReloadReport by services.module.hotReloadReportFlow.collectAsStateWithLifecycle()

            val coroutineScope = rememberCoroutineScope()
            val downloadXlCleanup = remember(coroutineScope) { DownloadXlCleanup(coroutineScope) }

            LaunchedEffect(allowLandscape) {
                activity.requestedOrientation = if (allowLandscape) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }

            val moduleActive = rememberModuleActivation(settings, restarts, serviceConnected, isModuleActive)






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
                LocalAppSettings provides settings,
                LocalDownloadXlCleanup provides downloadXlCleanup,
                LocalAppServices provides services,
                // A localized ConfigurationContext is not an Activity. Keep Activity-owned
                // launchers and back dispatch available to navigation entries and windows.
                LocalActivity provides activity,
                LocalActivityResultRegistryOwner provides activity,
                LocalOnBackPressedDispatcherOwner provides activity,
                LocalAppBlurMode provides AppBlurMode.entries[appBlurMode],
                LocalAppUiStyle provides appUiStyleFromStored(appUiStyle),
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
                        moduleActive = moduleActive,
                        hotReloadAvailable = staleTargets.isNotEmpty(),
                        hotReloading = hotReloading,
                        hotReloadTargets = staleTargets,
                        hotReloadReport = hotReloadReport,
                        pendingRestartScopes = pendingRestartScopes,
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
                    backdrop = backdrop,
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
                            if (!AdbTcpPort.reset(activity.applicationContext)) {
                                Toast.makeText(localizedContext, R.string.debug_adb_tcp_reset_failed, Toast.LENGTH_LONG).show()
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
