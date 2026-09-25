package com.takekazex.hypertweak.hook

import android.content.pm.ApplicationInfo
import android.content.Context
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadHandleStore
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.ModuleContext
import com.takekazex.hypertweak.hook.rules.systemui.AODHooker
import com.takekazex.hypertweak.hook.rules.systemui.AodWallpaperHandoffHooker
import com.takekazex.hypertweak.hook.rules.aod.AodStatusIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.AospSystemUiPluginBlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.AospVolumeHapticHooker
import com.takekazex.hypertweak.hook.rules.systemui.AospVolumeExtrasHooker
import com.takekazex.hypertweak.hook.rules.systemui.ExtendUnlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.ProxyLaunchHooker
import com.takekazex.hypertweak.hook.rules.systemui.UnlockClipboardHooker
import com.takekazex.hypertweak.hook.rules.lbe.LbeClipboardToastHooker
import com.takekazex.hypertweak.hook.rules.aicr.AicrDefaultBrowserHooker
import com.takekazex.hypertweak.hook.rules.systemui.HideFingerprintIcon
import com.takekazex.hypertweak.hook.rules.systemui.HideBottomBarHooker
import com.takekazex.hypertweak.hook.rules.systemui.HideLockscreenStatusBarHooker
import com.takekazex.hypertweak.hook.rules.systemui.NotificationHeaderClockSecondsHooker
import com.takekazex.hypertweak.hook.rules.systemui.NotificationHeaderHooker
import com.takekazex.hypertweak.hook.rules.systemui.NotificationHeaderWeatherHooker
import com.takekazex.hypertweak.hook.rules.systemui.NotificationMonetTextColorHooker
import com.takekazex.hypertweak.hook.rules.systemui.NotificationFontWeightHooker
import com.takekazex.hypertweak.hook.rules.systemui.SystemUiScreenshotSoundHooker
import com.takekazex.hypertweak.hook.rules.systemui.ImmediateMonetRefreshHooker
import com.takekazex.hypertweak.hook.rules.systemui.KeyguardFingerprintAvoidHooker
import com.takekazex.hypertweak.hook.rules.systemui.MediaCardHideAppIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.MediaCardHideDeviceSwitchHooker
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenAllNotificationsHooker
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenKeepNotificationsHooker
import com.takekazex.hypertweak.hook.rules.systemui.FocusNotificationWhitelistHooker
import com.takekazex.hypertweak.hook.rules.systemui.SystemUiBubbleNotificationWhitelistHooker
import com.takekazex.hypertweak.hook.rules.systemui.NotificationBlockFoldHooker
import com.takekazex.hypertweak.hook.rules.systemui.StatusBarHideSilentHooker
import com.takekazex.hypertweak.hook.rules.systemui.FreeformBlurTransitionHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CellularIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.WifiIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.HideCellularIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconManagerHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconPositionHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotTintHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IgnoreSysIconSettingsHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.duo.DuoSignalHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.StackedSignalHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CompoundIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.HideCarrierLabelHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterHeaderHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CcBatteryStyleHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.ControlCenterCarrierBlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.RegionSamplingHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CellularTypeIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.NotificationMaxNumberHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.LeftContainerHooker
import com.takekazex.hypertweak.hook.rules.module.ModuleStatusHooker
import com.takekazex.hypertweak.hook.rules.module.SettingsHooker
import com.takekazex.hypertweak.hook.rules.ime.AospImeConfig
import com.takekazex.hypertweak.hook.rules.ime.AospImeHooker
import com.takekazex.hypertweak.hook.rules.ime.AospImeSystemHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.AospAppInfoEntryHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.AospAppManagerEntryHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.BatteryInfoHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.BerserkModeHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.BubbleNotificationWhitelistHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.DetailedPowerDataHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.LowBatteryWarningHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.MoreBatteryInfoHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.PowerRankingHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.SecurityCoreBubbleAppListHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.WarningCountdownHooker
import com.takekazex.hypertweak.hook.rules.system.AospPackageInstallerHooker
import com.takekazex.hypertweak.hook.rules.system.SystemConfigHooker
import com.takekazex.hypertweak.hook.rules.system.ThemeDrmRevalidationHooker
import com.takekazex.hypertweak.hook.rules.system.CircleToSearchGestureHooker
import com.takekazex.hypertweak.hook.rules.system.ContextualSearchSystemHooker
import com.takekazex.hypertweak.hook.rules.system.DefaultAssistantHooker
import com.takekazex.hypertweak.hook.rules.system.PowerButtonCtsHooker
import com.takekazex.hypertweak.hook.rules.system.PasskeyHooker
import com.takekazex.hypertweak.hook.rules.system.SpatialAudioBlockerHooker
import com.takekazex.hypertweak.hook.rules.system.AonRuntimeGateHooker
import com.takekazex.hypertweak.hook.rules.system.VolumeKeyStepsHooker
import com.takekazex.hypertweak.hook.rules.system.AonGestureFeatureHooker
import com.takekazex.hypertweak.hook.rules.system.AdaptiveRefreshRuntimeHooker
import com.takekazex.hypertweak.hook.rules.system.ForceDarkAppListHooker
import com.takekazex.hypertweak.hook.rules.settings.BluetoothPluginHooker
import com.takekazex.hypertweak.hook.rules.settings.SpatialAudioHooker
import com.takekazex.hypertweak.hook.rules.settings.FastCameraSettingsHooker
import com.takekazex.hypertweak.hook.rules.settings.VisualPerceptionSettingsHooker
import com.takekazex.hypertweak.hook.rules.settings.AonGestureSettingsHooker
import com.takekazex.hypertweak.hook.rules.settings.AdaptiveRefreshSettingsHooker
import com.takekazex.hypertweak.hook.rules.settings.ChannelKeyguardToggleHooker
import com.takekazex.hypertweak.hook.rules.settings.GlobalSettingsInterfaceHooker
import com.takekazex.hypertweak.hook.rules.settings.NotificationMoreSettingsHooker
import com.takekazex.hypertweak.hook.rules.settings.NotificationBadgeSettingsHooker
import com.takekazex.hypertweak.hook.rules.settings.GoogleServicesSettingsHooker
import com.takekazex.hypertweak.hook.rules.phone.VideoRingbackHooker
import com.takekazex.hypertweak.hook.rules.system.FcmLiveSystemHooker
import com.takekazex.hypertweak.hook.rules.systemui.SystemUIPluginHooker
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenChargingDetailHooker
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenBottomTextHooker
import com.takekazex.hypertweak.hook.rules.systemui.ControlCenterCardsEditHooker
import com.takekazex.hypertweak.hook.rules.systemui.glass.GlassMaterialHooker
import com.takekazex.hypertweak.hook.rules.module.RestartBroadcastHooker
import com.takekazex.hypertweak.hook.rules.powerkeeper.FcmLivePowerKeeperHooker
import com.takekazex.hypertweak.hook.rules.gms.QuickSharePhenotypeHooker
import com.takekazex.hypertweak.hook.rules.googleapp.GoogleAppRuntime
import com.takekazex.hypertweak.hook.rules.mediaeditor.MediaEditorWatermarkHooker
import com.takekazex.hypertweak.hook.rules.personalassistant.ModelSpoofHooker
import com.takekazex.hypertweak.hook.rules.camera.CameraWatermarkHooker
import com.takekazex.hypertweak.hook.rules.camera.CameraImpersonationHooker
import com.takekazex.hypertweak.hook.rules.camera.CameraUltraQualityHooker
import com.takekazex.hypertweak.hook.rules.camera.CameraDeviceMismatchHooker
import com.takekazex.hypertweak.hook.rules.camera.CameraLegendaryProfileHooker
import com.takekazex.hypertweak.hook.rules.xmsf.UnlockFocusAuthHooker
import com.takekazex.hypertweak.hook.rules.downloads.DownloadXlLogDirectoryHooker
import com.takekazex.hypertweak.hook.rules.downloads.DownloadUiHooker
import com.takekazex.hypertweak.hook.rules.guardprovider.GuardProviderEnvironmentCheckHooker
import com.takekazex.hypertweak.hook.rules.guardprovider.GuardProviderUploadAppListHooker
import com.takekazex.hypertweak.hook.rules.milink.MiLinkHpplayHooker
import com.takekazex.hypertweak.hook.rules.trustservice.MiTrustRiskMonitoringHooker
import com.takekazex.hypertweak.hook.rules.thememanager.ThemeManagerRightsCheckHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.ApplicationAttachCallback
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class HookEntry : XposedModule() {
    private companion object {
        /**
         * The launcher process. It carries no dex of its own for the module's hooks, but it is in
         * the declared scope so LSPosed injects both the native payload (which owns the Dart
         * rules) and this module's Java (which is the only side able to read the preferences).
         */
        const val LAUNCHER_PACKAGE = "com.miui.home"
    }

    /** Bounded retry window for the transient daemon remote-preferences outage. */
    private val MAX_PREFS_INIT_RETRIES = 4
    private val PREFS_RETRY_DELAY_MS = 500L

    private val injectedPackages = ConcurrentHashMap.newKeySet<String>()
    private val rootHookers = ConcurrentHashMap.newKeySet<BaseHooker>()
    private val packageStates = ConcurrentHashMap<String, HotReloadPackageState>()
    private val pendingAppContextPackages = ConcurrentHashMap.newKeySet<String>()
    private val preferenceRetryGeneration = AtomicLong(0L)
    private lateinit var processName: String
    private var isSystemServer: Boolean = false
    private var systemServerClassLoader: ClassLoader? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        // Process boundary. The process identity is recorded unconditionally (later callbacks and
        // the debug log need it), then everything else runs behind a boundary: a module failure
        // here must be logged, not thrown back into the host's module-load path.
        processName = param.processName
        isSystemServer = param.isSystemServer
        try {
            preferenceRetryGeneration.incrementAndGet()
            // Initialize EzXposed with the module interface
            EzXposed.initOnModuleLoaded(this, param)
            DebugLog.setProcessTag(processName)
            DebugLog.bindXposed(this)
            initPreferences()
            DebugLog.ensureSession()
            DebugLog.d("HookEntry", "module loaded process=$processName isSystemServer=$isSystemServer")
            // The native payload is injected before this callback runs. Record what it reports for
            // this process so a packaging or injection failure shows up in the module log without a
            // separate logcat capture.
            DebugLog.i("NativeRules", NativeRules.describe())
            publishNativeRuleSwitches()
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "module load handling failed", t)
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        EzXposed.initOnSystemServerStarting(param)
        systemServerClassLoader = param.classLoader
        DebugLog.d("HookEntry", "system_server starting")
        // Safety net: a structural failure while dispatching the framework hooks (any hooker failing
        // to attach, or the dispatch itself blowing up) must land in the in-app debug log so it can
        // be diagnosed from the app's Logs page without digging through LSPosed's daemon files.
        // Each attachHooker already isolates per-hooker failures; this catches anything that escapes
        // it (e.g. an unguarded preference read) and records it, then rethrows so the host still
        // sees the failure.
        try {
            dispatchSystemServerHookers(param.classLoader)
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "system_server hook dispatch failed", t)
            throw t
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (!injectedPackages.add(param.packageName)) return
        // Process boundary: a failure while installing this package's hooks must be isolated and
        // logged rather than escaping into the host app's own package-load path.
        try {
            EzXposed.initOnPackageLoaded(param)
            EzReflect.init(param.defaultClassLoader)
            recordPackageState(
                packageName = param.packageName,
                classLoader = param.defaultClassLoader,
                appInfo = param.applicationInfo,
                isFirstPackage = param.isFirstPackage,
                isPackageReady = false,
                appContext = null
            )
            DebugLog.d(
                "HookEntry",
                "package loaded package=${param.packageName} process=$processName first=${param.isFirstPackage}"
            )

            dispatchPackageHookers(
                packageName = param.packageName,
                classLoader = param.defaultClassLoader,
                appInfo = param.applicationInfo,
                isFirstPackage = param.isFirstPackage
            )
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "package load handling failed package=${param.packageName}", t)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // Process boundary, same rationale as onPackageLoaded.
        try {
            // Establishes the target snapshot required for hot reload state restore.
            EzXposed.initOnPackageReady(param)
            recordPackageState(
                packageName = param.packageName,
                classLoader = param.classLoader,
                appInfo = param.applicationInfo,
                isFirstPackage = false,
                isPackageReady = true,
                appContext = runCatching { EzXposed.appContextOrNull }.getOrNull(),
                pluginStates = currentPluginStates(param.packageName)
            )

            handlePackageReadyContext(param.packageName, param.classLoader)

            if (param.packageName == "com.android.systemui") {
                HideBottomBarHooker.onPackageReady(packageStates[param.packageName]?.appContext, param.classLoader)
            }
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "package ready handling failed package=${param.packageName}", t)
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        preferenceRetryGeneration.incrementAndGet()
        DebugLog.d(
            "HookEntry",
            "hot reloading old generation process=$processName packages=${packageStates.size} roots=${rootHookers.size} modes=${hotReloadModeSummary()}"
        )
        if (GoogleAppRuntime.isResolvingDex) {
            DebugLog.w(
                "HookEntry",
                "deferred hot reload during Google App DexKit resolution process=$processName"
            )
            return false
        }
        val ready = runCatching {
            refreshHotReloadSnapshots()
            if (!DexKitManager.prepareForHotReload()) {
                error("DexKit native bridge users are still active")
            }
            val hyperTweakState = HotReloadState.save(
                processName = processName,
                isSystemServer = isSystemServer,
                systemServerClassLoader = systemServerClassLoader,
                packages = packageStates.values,
                hookerStates = rootHookers.associate { it.hookerName to it.saveHotReloadState() }
            )
            rootHookers.forEach { it.prepareForHotReload() }
            rootHookers.forEach { it.resetAfterHotReloadPrepared() }
            rootHookers.clear()
            param.setSavedInstanceState(hyperTweakState)
            DebugLog.d("HookEntry", "hot reload preparation completed; old generation can retire")
            DebugLog.prepareForHotReload()
        }.onFailure { t ->
            DexKitManager.cancelHotReloadPreparation()
            DebugLog.e("HookEntry", "hot reload preparation failed; keeping old generation active", t)
        }.isSuccess
        return ready
    }

    private fun refreshHotReloadSnapshots() {
        packageStates["com.android.systemui"]?.let { state ->
            packageStates["com.android.systemui"] = state.copy(
                pluginStates = SystemUIPluginHooker.snapshotHotReloadPlugins()
            )
        }
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        processName = param.processName
        isSystemServer = param.isSystemServer
        preferenceRetryGeneration.incrementAndGet()
        EzXposed.initOnModuleLoaded(this, param)
        DebugLog.setProcessTag(processName)
        DebugLog.bindXposed(this)
        initPreferences()
        DebugLog.ensureSession()
        val restoredState = HotReloadState.restore(param.savedInstanceState)
        val oldHandles = HotReloadHandleStore(param.oldHookHandles)
        val oldHandleIds = oldHandles.ids
        DebugLog.d(
            "HookEntry",
            "hot reloaded process=$processName packages=${restoredState?.packages?.map { it.packageName }} oldHandles=${oldHandles.totalCount} oldIds=${oldHandles.idCount} unnamed=${oldHandles.unnamedCount} duplicateIds=${oldHandles.duplicateIdCount}"
        )

        injectedPackages.clear()
        rootHookers.clear()
        packageStates.clear()
        pendingAppContextPackages.clear()

        if (restoredState == null) {
            DebugLog.w("HookEntry", "hot reloaded without restorable target state")
            unhookRemainingOldHandles(oldHandles)
            return
        }

        // Match upstream's ordering: stable Google callbacks are attached to the carried old
        // executable before the new generation starts its DexKit pass. Retired investigation IDs
        // are neutralized here as well, so they cannot keep stale behavior alive after an update.
        val googleProcess = processName == GoogleAppRuntime.PACKAGE ||
            processName.startsWith("${GoogleAppRuntime.PACKAGE}:") ||
            restoredState.packages.any { it.packageName == GoogleAppRuntime.PACKAGE }
        var googleReplaced = 0
        if (googleProcess) {
            oldHandles.remainingHandles().forEach { handle ->
                if (GoogleAppRuntime.replaceOldHandle(handle)) {
                    oldHandles.markHandled(handle)
                    googleReplaced++
                }
            }
        }
        if (googleReplaced > 0) {
            DebugLog.d("HookEntry", "replaced carried Google hooks=$googleReplaced")
        }

        processName = restoredState.processName
        isSystemServer = restoredState.isSystemServer
        systemServerClassLoader = restoredState.systemServerClassLoader

        if (restoredState.isSystemServer) {
            val targetClassLoader = restoredState.systemServerClassLoader ?: run {
                DebugLog.w("HookEntry", "hot reloaded system_server without classLoader")
                unhookRemainingOldHandles(oldHandles)
                return
            }
            EzReflect.init(targetClassLoader)
            dispatchSystemServerHookers(targetClassLoader, oldHandles)
        } else {
            restoredState.packages.forEach { state ->
                recordPackageState(
                    packageName = state.packageName,
                    classLoader = state.classLoader,
                    appInfo = state.appInfo,
                    isFirstPackage = state.isFirstPackage,
                    isPackageReady = state.isPackageReady,
                    appContext = state.appContext,
                    pluginStates = state.pluginStates
                )
                injectedPackages.add(state.packageName)
                if (state.appContext != null) {
                    pendingAppContextPackages.remove(state.packageName)
                }
                EzReflect.init(state.classLoader)
                dispatchPackageHookers(
                    packageName = state.packageName,
                    classLoader = state.classLoader,
                    appInfo = state.appInfo,
                    isFirstPackage = false,
                    replacementHandles = oldHandles
                )
                if (state.isPackageReady) {
                    onRestoredPackageReady(state, oldHandles)
                }
            }
        }

        restoreHookerStates(restoredState.hookerStates)

        logHotReloadHandleDiff(oldHandleIds, oldHandles)
        unhookRemainingOldHandles(oldHandles)
        retryHookersAfterHotReload()
        if (!isSystemServer && processName == "com.android.systemui") {
            val context = restoredState.packages.firstOrNull { it.packageName == processName }?.appContext
            if (context != null) {
                com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHotReloadRecovery.schedule(context)
            } else {
                DebugLog.w("HookEntry", "SystemUI hot reload recovery missing application context")
            }
        }
        DebugLog.i("HookEntry", "hot reload replacement ready process=$processName roots=${rootHookers.size}")
    }

    /** Recover a hooker whose in-place replacement failed without restarting the host process. */
    private fun retryHookersAfterHotReload() {
        rootHookers.toList().forEach { it.retryHookIfNeeded() }
    }

    private fun logHotReloadHandleDiff(
        oldHandleIds: Set<String>,
        oldHandles: HotReloadHandleStore
    ) {
        val newHandleIds = rootHookers.flatMap { it.collectManagedHookHandles() }
            .mapNotNull { it.id }
            .toSet()
        val replacedCount = oldHandles.totalCount - oldHandles.remainingCount
        DebugLog.d(
            "HookEntry",
            "hot reload registered new handles=${newHandleIds.size} replaced=$replacedCount matched=${newHandleIds.intersect(oldHandleIds).size} added=${newHandleIds.minus(oldHandleIds).size} remainingOld=${oldHandles.remainingCount}"
        )
    }

    private fun unhookRemainingOldHandles(handles: HotReloadHandleStore) {
        var unhookedCount = 0
        var unhookFailedCount = 0
        handles.remainingHandles().forEach { handle ->
            runCatching {
                handle.unhook()
                handles.markHandled(handle)
                unhookedCount++
            }.onFailure {
                unhookFailedCount++
                DebugLog.w("HookEntry", "failed to unhook old handle ${handle.id}", it)
            }
        }
        DebugLog.d(
            "HookEntry",
            "hot reload removed unmatched old handles ok=$unhookedCount failed=$unhookFailedCount"
        )
    }

    private fun restoreHookerStates(states: Map<String, Any?>) {
        rootHookers.forEach { hooker ->
            if (states.containsKey(hooker.hookerName)) {
                runCatching { hooker.restoreHotReloadState(states[hooker.hookerName]) }
                    .onFailure { DebugLog.e("HookEntry", "failed to restore ${hooker.hookerName}", it) }
            }
        }
    }

    private fun recordPackageState(
        packageName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo?,
        isFirstPackage: Boolean,
        isPackageReady: Boolean,
        appContext: Context?,
        pluginStates: List<HotReloadPluginState> = emptyList()
    ) {
        val old = packageStates[packageName]
        packageStates[packageName] = HotReloadPackageState(
            packageName = packageName,
            processName = processName,
            classLoader = classLoader,
            appInfo = appInfo ?: old?.appInfo,
            isFirstPackage = old?.isFirstPackage ?: isFirstPackage,
            isPackageReady = old?.isPackageReady == true || isPackageReady,
            appContext = appContext ?: old?.appContext,
            pluginStates = if (pluginStates.isNotEmpty()) pluginStates else old?.pluginStates.orEmpty()
        )
        if (packageStates[packageName]?.appContext != null) {
            pendingAppContextPackages.remove(packageName)
        }
    }

    private fun handlePackageReadyContext(packageName: String, classLoader: ClassLoader) {
        val appContext = packageStates[packageName]?.appContext ?: runCatching {
            EzXposed.appContextOrNull
        }.getOrNull()?.also { context ->
            recordPackageState(
                packageName = packageName,
                classLoader = classLoader,
                appInfo = packageStates[packageName]?.appInfo,
                isFirstPackage = false,
                isPackageReady = true,
                appContext = context,
                pluginStates = currentPluginStates(packageName)
            )
        }

        if (appContext != null) {
            onPackageReadyContextAvailable(packageName, appContext)
            return
        }

        DebugLog.d("HookEntry", "package ready package=$packageName waiting for app context")
        if (!pendingAppContextPackages.add(packageName)) return

        EzXposed.runOnApplicationAttach(object : ApplicationAttachCallback {
            override fun onApplicationAttached(context: Context) {
                val appContext = context.applicationContext ?: context
                if (appContext.packageName != packageName) return
                if (!pendingAppContextPackages.remove(packageName)) return

                recordPackageState(
                    packageName = packageName,
                    classLoader = classLoader,
                    appInfo = packageStates[packageName]?.appInfo,
                    isFirstPackage = false,
                    isPackageReady = true,
                    appContext = appContext,
                    pluginStates = currentPluginStates(packageName)
                )
                onPackageReadyContextAvailable(packageName, appContext)
            }
        })
    }

    private fun onPackageReadyContextAvailable(packageName: String, appContext: Context) {
        Preferences.initLocalCache(appContext)
        RestartBroadcastHooker.register(appContext)
        if (packageName == BatteryInfoHooker.PACKAGE && processName == packageName) {
            BatteryInfoHooker.onPackageReady(appContext)
        }
        if (packageName == "com.android.systemui") {
            ProxyLaunchHooker.register(appContext)
            ExtendUnlockHooker.syncTrustAgent(appContext)
            StackedSignalHooker.onPackageReady(appContext)
            ControlCenterCarrierBlockHooker.onPackageReady(appContext)
            LeftContainerHooker.onPackageReady(appContext)
        }
        if (packageName == "com.google.android.gms") {
            QuickSharePhenotypeHooker.onPackageReady(appContext)
        }
        DebugLog.d("HookEntry", "package ready package=$packageName context=${appContext.packageName}")
    }

    private fun hotReloadModeSummary(): String {
        return HotReloadMode.entries.joinToString(prefix = "{", postfix = "}") { mode ->
            val names = rootHookers
                .filter { it.hotReloadMode == mode }
                .map { it.hookerName }
                .sorted()
            "${mode.name}=$names"
        }
    }

    private fun onRestoredPackageReady(
        state: HotReloadPackageState,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        val appContext = state.appContext ?: runCatching { EzXposed.appContextOrNull }.getOrNull()
        if (appContext != null) {
            Preferences.initLocalCache(appContext)
            RestartBroadcastHooker.register(appContext)
            if (state.packageName == BatteryInfoHooker.PACKAGE && processName == state.packageName) {
                BatteryInfoHooker.onPackageReady(appContext)
            }
            if (state.packageName == "com.android.systemui") {
                ProxyLaunchHooker.register(appContext)
                ExtendUnlockHooker.syncTrustAgent(appContext)
                StackedSignalHooker.onPackageReady(appContext)
                LeftContainerHooker.onPackageReady(appContext)
            }
            if (state.packageName == "com.google.android.gms") {
                QuickSharePhenotypeHooker.onPackageReady(appContext)
            }
            DebugLog.d("HookEntry", "restored package ready package=${state.packageName} context=${appContext.packageName}")
        } else {
            DebugLog.w("HookEntry", "restored package ready package=${state.packageName} without app context")
        }

        if (state.packageName == "com.android.systemui") {
            HideBottomBarHooker.setHotReloadReplacementHandles(replacementHandles)
            runCatching {
                HideBottomBarHooker.onPackageReady(appContext, state.classLoader)
            }.also {
                HideBottomBarHooker.setHotReloadReplacementHandles(null)
            }.onFailure { t ->
                DebugLog.e("HookEntry", "failed to restore SystemUI package ready hooks", t)
            }
            SystemUIPluginHooker.setHotReloadReplacementHandles(replacementHandles)
            runCatching {
                SystemUIPluginHooker.restoreHotReloadPlugins(state.pluginStates)
            }.also {
                SystemUIPluginHooker.setHotReloadReplacementHandles(null)
            }.onFailure { t ->
                DebugLog.e("HookEntry", "failed to restore SystemUI plugin hooks", t)
            }
        }
    }

    private fun currentPluginStates(packageName: String): List<HotReloadPluginState> {
        return if (packageName == "com.android.systemui") {
            SystemUIPluginHooker.snapshotHotReloadPlugins()
        } else {
            emptyList()
        }
    }

    private fun initPreferences() {
        if (tryInitPreferences()) return
        val retryGeneration = preferenceRetryGeneration.get()
        // The LSPosed daemon's remote-preferences channel is unavailable (e.g. it returns
        // null / throws "Framework returns null"). Preferences is left uninitialized; getters
        // fall back to the per-process cache rather than silently reading defaults, and the
        // flag lets the UI surface the degraded state.
        //
        // The outage may be transient (the daemon not ready when this process loads, or the
        // daemon restarting), so retry with bounded backoff before giving up for this process
        // lifetime. Once the bridge recovers, hookers that skipped installation are retried
        // in-place instead of permanently retaining their default-off state.
        Thread({
            var attempt = 0
            while (attempt < MAX_PREFS_INIT_RETRIES) {
                runCatching { Thread.sleep(PREFS_RETRY_DELAY_MS * (1L shl attempt)) }
                if (preferenceRetryGeneration.get() != retryGeneration) return@Thread
                if (tryInitPreferences()) {
                    retryPreferenceGatedHookers(retryGeneration)
                    publishNativeRuleSwitches()
                    break
                }
                attempt++
            }
        }, "HyperTweak-PrefsRetry").apply { isDaemon = true }.start()
    }

    /** Re-run only hookers that had no installed handles during the transient prefs outage. */
    private fun retryPreferenceGatedHookers(retryGeneration: Long) {
        if (preferenceRetryGeneration.get() != retryGeneration) return
        rootHookers.toList().forEach { it.retryHookIfNeeded() }
    }

    /**
     * Hands the launcher-side rule switches to the payload, in the launcher process only.
     *
     * `NativeRuleConfig` publishes the same values as a file in the module's shared media
     * directory, but that file is unreadable from here: the launcher is a `platform_app_36`
     * process with an ordinary app uid, is neither the file's owner nor in its `media_rw` group,
     * and scoped storage refuses it. The module's Java is injected into the launcher as well, so
     * this call is what actually reaches the rules — see [NativeRules.applyRuleSwitches].
     */
    private fun publishNativeRuleSwitches() {
        if (processName != LAUNCHER_PACKAGE) return
        runCatching {
            NativeRules.applyRuleSwitches(
                hideRecentsClearButton = Preferences.hideRecentsClearButton(),
                openedFolderColumns = Preferences.openedFolderColumns(),
                contextualSearchLongPress = Preferences.contextualSearchLongPress()
            )
        }.onFailure { t ->
            DebugLog.w("HookEntry", "native rule switch publish failed", t)
        }
    }

    /** Runs one attempt at binding the daemon's remote preferences; true on success. */
    private fun tryInitPreferences(): Boolean {
        return try {
            val remotePrefs = getRemotePreferences(Preferences.NAME)
            Preferences.init(remotePrefs)
            DebugLog.d("HookEntry", "processName=$processName loaded remotePrefs keys=${remotePrefs.all.keys}")
            true
        } catch (t: Throwable) {
            Preferences.noteRemoteBackendUnavailable()
            DebugLog.e("HookEntry", "failed to init Preferences (remote channel unavailable; using cache fallback)", t)
            false
        }
    }

    private fun dispatchSystemServerHookers(
        classLoader: ClassLoader,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        val ctx = ModuleContext(
            processName = processName,
            packageName = "system",
            isSystemServer = true,
            appContext = null
        )
        attachHooker(SystemConfigHooker, classLoader, ctx, replacementHandles)
        attachHooker(ContextualSearchSystemHooker, classLoader, ctx, replacementHandles)
        attachHooker(PowerButtonCtsHooker, classLoader, ctx, replacementHandles)
        // Aligns the AOSP assistant setting with the assistant selected in 默认应用 so the
        // platform's own assist path can launch it (see the hooker's comment for what HyperOS
        // leaves inconsistent).
        attachHooker(DefaultAssistantHooker, classLoader, ctx, replacementHandles)
        // Binds the launcher's gesture-bar long press to a route that reaches Circle to Search
        // instead of XiaoAI; the native terminal patch alone is not enough (see the hooker).
        attachHooker(CircleToSearchGestureHooker, classLoader, ctx, replacementHandles)
        attachHooker(PasskeyHooker, classLoader, ctx, replacementHandles)
        attachHooker(FcmLiveSystemHooker, classLoader, ctx, replacementHandles)
        attachHooker(AospPackageInstallerHooker, classLoader, ctx, replacementHandles)
        // AON visual-perception runtime gates (感知锁屏/靠近亮屏/非注视感知) are resource bools read by
        // miui-services inside system_server; the Settings-side unlock alone cannot activate them.
        attachHooker(AonRuntimeGateHooker, classLoader, ctx, replacementHandles)
        attachHooker(VolumeKeyStepsHooker, classLoader, ctx, replacementHandles)
        // Experimental: let the AON air-gesture controller honour left/right + double-press, which
        // the stock ROM always zeroes for AON (it only enables up/down).
        attachHooker(AonGestureFeatureHooker, classLoader, ctx, replacementHandles)
        // 自适应刷新率Pro (Mimotion PWM): force ro.display.enable_pwm_switch so
        // DisplayFeatureManagerService's boot-time setDimmingMode() re-applies the Secure mode after
        // a reboot on builds where the vendor prop is unset (see AdaptiveRefreshSettingsHooker).
        attachHooker(AdaptiveRefreshRuntimeHooker, classLoader, ctx, replacementHandles)
        // Dark-mode app data is returned through the uimode Binder. Keep system_server as the
        // single source of truth so Settings and app processes observe the same persistent state.
        attachHooker(ForceDarkAppListHooker, classLoader, ctx, replacementHandles)
        // Stops the framework's periodic theme DRM re-validation from restoring the default theme
        // when a locally imported (third-party) theme is applied.
        attachHooker(ThemeDrmRevalidationHooker, classLoader, ctx, replacementHandles)
        if (AospImeConfig.isEnabled()) {
            attachHooker(AospImeSystemHooker, classLoader, ctx, replacementHandles)
        }
    }

    private fun dispatchPackageHookers(
        packageName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo?,
        isFirstPackage: Boolean,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        val ctx = ModuleContext(
            processName = processName,
            packageName = packageName,
            isSystemServer = isSystemServer,
            isFirstPackage = isFirstPackage,
            isPackageReady = false,
            appInfo = appInfo,
            appContext = null
        )

        // Input methods are chosen by the user, so they cannot be listed in the `when` below. All
        // the targets are boot-classpath classes present in every process, so a wrong package only
        // installs hooks that never fire; the picker does the real validation.
        if (AospImeConfig.shouldHookImePackage(packageName)) {
            attachHooker(AospImeHooker, classLoader, ctx, replacementHandles)
        }

        when (packageName) {
            "com.android.systemui" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(AODHooker, classLoader, ctx, replacementHandles)
                attachHooker(AodWallpaperHandoffHooker, classLoader, ctx, replacementHandles)
                attachHooker(HideFingerprintIcon, classLoader, ctx, replacementHandles)
                attachHooker(HideLockscreenStatusBarHooker, classLoader, ctx, replacementHandles)
                attachHooker(NotificationHeaderClockSecondsHooker, classLoader, ctx, replacementHandles)
                attachHooker(NotificationHeaderHooker, classLoader, ctx, replacementHandles)
                attachHooker(NotificationHeaderWeatherHooker, classLoader, ctx, replacementHandles)
                attachHooker(NotificationMonetTextColorHooker, classLoader, ctx, replacementHandles)
                attachHooker(NotificationFontWeightHooker, classLoader, ctx, replacementHandles)
                attachHooker(SystemUiScreenshotSoundHooker, classLoader, ctx, replacementHandles)
                attachHooker(KeyguardFingerprintAvoidHooker, classLoader, ctx, replacementHandles)
                attachHooker(LockscreenChargingDetailHooker, classLoader, ctx, replacementHandles)
                attachHooker(LockscreenBottomTextHooker, classLoader, ctx, replacementHandles)
                attachHooker(ImmediateMonetRefreshHooker, classLoader, ctx, replacementHandles)
                attachHooker(SystemUIPluginHooker, classLoader, ctx, replacementHandles)
                attachHooker(AospSystemUiPluginBlockHooker, classLoader, ctx, replacementHandles)
                attachHooker(AospVolumeHapticHooker, classLoader, ctx, replacementHandles)
                attachHooker(AospVolumeExtrasHooker, classLoader, ctx, replacementHandles)
                attachHooker(ExtendUnlockHooker, classLoader, ctx, replacementHandles)
                attachHooker(ProxyLaunchHooker, classLoader, ctx, replacementHandles)
                attachHooker(UnlockClipboardHooker, classLoader, ctx, replacementHandles)
                attachHooker(HideBottomBarHooker, classLoader, ctx, replacementHandles)
                attachHooker(CellularIconHooker, classLoader, ctx, replacementHandles)
                attachHooker(WifiIconHooker, classLoader, ctx, replacementHandles)
                attachHooker(HideCellularIconHooker, classLoader, ctx, replacementHandles)
                attachHooker(IconManagerHooker, classLoader, ctx, replacementHandles)
                attachHooker(IconPositionHooker, classLoader, ctx, replacementHandles)
                attachHooker(IconSlotTintHooker, classLoader, ctx, replacementHandles)
                attachHooker(IgnoreSysIconSettingsHooker, classLoader, ctx, replacementHandles)
                attachHooker(LeftContainerHooker, classLoader, ctx, replacementHandles)
                attachHooker(DuoSignalHooker, classLoader, ctx, replacementHandles)
                attachHooker(StackedSignalHooker, classLoader, ctx, replacementHandles)
                attachHooker(CompoundIconHooker, classLoader, ctx, replacementHandles)
                attachHooker(HideCarrierLabelHooker, classLoader, ctx, replacementHandles)
                attachHooker(ControlCenterHeaderHooker, classLoader, ctx, replacementHandles)
                attachHooker(CcBatteryStyleHooker, classLoader, ctx, replacementHandles)
                attachHooker(ControlCenterCarrierBlockHooker, classLoader, ctx, replacementHandles)
                attachHooker(RegionSamplingHooker, classLoader, ctx, replacementHandles)
                attachHooker(CellularTypeIconHooker, classLoader, ctx, replacementHandles)
                attachHooker(NotificationMaxNumberHooker, classLoader, ctx, replacementHandles)
                attachHooker(GlassMaterialHooker, classLoader, ctx, replacementHandles)
                attachHooker(ControlCenterCardsEditHooker(), classLoader, ctx, replacementHandles)
                attachHooker(MediaCardHideAppIconHooker, classLoader, ctx, replacementHandles)
                attachHooker(MediaCardHideDeviceSwitchHooker, classLoader, ctx, replacementHandles)
                attachHooker(LockscreenAllNotificationsHooker, classLoader, ctx, replacementHandles)
                attachHooker(LockscreenKeepNotificationsHooker, classLoader, ctx, replacementHandles)
                attachHooker(FocusNotificationWhitelistHooker, classLoader, ctx, replacementHandles)
                attachHooker(SystemUiBubbleNotificationWhitelistHooker, classLoader, ctx, replacementHandles)
                // Notification fold suppression (its own switch) and the status-bar "silent
                // notification" filter repair, which rides on the 恢复更多通知设置 switch and has no
                // preference of its own — it only makes the stock system switch work again.
                attachHooker(NotificationBlockFoldHooker, classLoader, ctx, replacementHandles)
                attachHooker(StatusBarHideSilentHooker, classLoader, ctx, replacementHandles)
                // 多任务过渡模糊: the WM Shell multitasking classes reach this process through the
                // `com.miui.wm.shell` shared library declared in SystemUI's manifest, so the plain
                // SystemUI class loader resolves them (same channel as HideBottomBarHooker's
                // MiuiDecorationBottomView).
                attachHooker(FreeformBlurTransitionHooker, classLoader, ctx, replacementHandles)
                // ControlCenterCardResizeHooker is NOT attached here: its target classes live in
                // the miui.systemui.plugin APK, whose PathClassLoader only exists after
                // PluginInstance.loadPlugin() runs — SystemUIPluginHooker attaches it with that
                // loader (see attachPluginHooker).
            }
            "com.miui.screenshot" -> {
                // HyperOS delegates normal screenshots to this process. Its provider hard-codes
                // screenshot.ogg, which is the short AOSP-style click on this CN build; redirect
                // that URI to the ROM's longer shutter sound when the lockscreen-bar tweak is on.
                attachHooker(SystemUiScreenshotSoundHooker, classLoader, ctx, replacementHandles)
            }
            "com.lbe.security.miui" -> {
                attachHooker(LbeClipboardToastHooker, classLoader, ctx, replacementHandles)
            }
            "com.miui.home" -> {
                // The launcher rules need no Java hook here: LSPosed injects only the native
                // payload into the launcher, so feature switches reach it through
                // NativeRuleConfig rather than through this process.
            }
            "com.miui.aod" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(AODHooker, classLoader, ctx, replacementHandles)
                attachHooker(AodStatusIconHooker(), classLoader, ctx, replacementHandles)
            }
            "com.android.settings" -> {
                // Flip Settings' process-local MIUI build flags before its own classes initialize.
                attachHooker(GlobalSettingsInterfaceHooker, classLoader, ctx, replacementHandles)
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(SettingsHooker, classLoader, ctx, replacementHandles)
                attachHooker(AODHooker, classLoader, ctx, replacementHandles)
                attachHooker(PasskeyHooker, classLoader, ctx, replacementHandles)
                // The Bluetooth extension is loaded by Settings, so its UI hook must be
                // attached in this process as well as the Bluetooth service process.
                attachHooker(BluetoothPluginHooker, classLoader, ctx, replacementHandles)
                attachHooker(SpatialAudioHooker(), classLoader, ctx, replacementHandles)
                attachHooker(FastCameraSettingsHooker, classLoader, ctx, replacementHandles)
                attachHooker(VisualPerceptionSettingsHooker, classLoader, ctx, replacementHandles)
                // Experimental: link the orphaned 左右挥手 / 隔空暂停或播放 pages into the AON
                // 隔空手势 landing list (stock ROM never references those fragments).
                attachHooker(AonGestureSettingsHooker, classLoader, ctx, replacementHandles)
                // 自适应刷新率Pro (Mimotion PWM): force ro.display.enable_pwm_switch so the
                // 显示与亮度 mimotion_pwm_enable row is not removed by MiuiDisplaySettings on
                // builds where the vendor prop is unset (see AdaptiveRefreshRuntimeHooker).
                attachHooker(AdaptiveRefreshSettingsHooker, classLoader, ctx, replacementHandles)
                // Reveal the per-channel 锁屏通知（allow_keyguard）switch in the notification channel
                // page; only effective when the SystemUI-side lockscreen-all-notifications hook is on.
                attachHooker(ChannelKeyguardToggleHooker, classLoader, ctx, replacementHandles)
                // Reveal + wire up the channel page's importance drop-down and badge checkbox
                // (HyperOS ships both as shells with no write-back listener).
                attachHooker(NotificationMoreSettingsHooker, classLoader, ctx, replacementHandles)
                attachHooker(NotificationBadgeSettingsHooker, classLoader, ctx, replacementHandles)
                // Restore Settings' own Google services home-page header on domestic builds.
                attachHooker(GoogleServicesSettingsHooker, classLoader, ctx, replacementHandles)
            }
            "com.android.phone" -> {
                // The native HyperPhone page delegates the CRBT capability and setter to this
                // telephony service; keep both the display gate and modem-setting boundary here.
                attachHooker(VideoRingbackHooker, classLoader, ctx, replacementHandles)
            }
            "com.xiaomi.phone" -> {
                // HyperPhone caches the CRBT capability it read from the telephony service above,
                // so disabling video ringback only takes effect once this UI process restarts.
                // There is deliberately no feature hook here — the package is scoped so the
                // restart receiver registered in onPackageReady can kill it without root.
            }
            "com.xiaomi.aon" -> {
                // AON service/attention process. No feature hook attaches here: the
                // visual-perception / air-gesture capability gates live in com.android.settings
                // (MiuiUtils, VisualPerceptionSettingsHooker) and in system_server
                // (AonRuntimeGateHooker, AonGestureFeatureHooker). The process is still scoped on
                // purpose: every scoped package gets the in-process restart receiver registered by
                // onPackageReady (see RestartBroadcastHooker.register), which lets the user restart
                // the attention service from the module's restart picker without root.
            }
            "com.miui.securitycenter" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(PasskeyHooker, classLoader, ctx, replacementHandles)
                attachHooker(AospAppInfoEntryHooker, classLoader, ctx, replacementHandles)
                attachHooker(AospAppManagerEntryHooker, classLoader, ctx, replacementHandles)
                // Only the main application process owns the on-demand battery request receiver.
                // Auxiliary Security Center processes must not start another sampling worker.
                if (processName == packageName) {
                    attachHooker(BatteryInfoHooker, classLoader, ctx, replacementHandles)
                }
                attachHooker(BerserkModeHooker, classLoader, ctx, replacementHandles)
                attachHooker(BubbleNotificationWhitelistHooker, classLoader, ctx, replacementHandles)
                attachHooker(LowBatteryWarningHooker, classLoader, ctx, replacementHandles)
                attachHooker(DetailedPowerDataHooker, classLoader, ctx, replacementHandles)
                attachHooker(PowerRankingHooker, classLoader, ctx, replacementHandles)
                attachHooker(MoreBatteryInfoHooker, classLoader, ctx, replacementHandles)
                attachHooker(WarningCountdownHooker, classLoader, ctx, replacementHandles)
                // Security Center ships its own copy of the vendor clipboard-read overlay and is the
                // one that actually builds it; see LbeClipboardToastHooker.
                attachHooker(LbeClipboardToastHooker, classLoader, ctx, replacementHandles)
            }
            "com.miui.securitycore" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(SecurityCoreBubbleAppListHooker, classLoader, ctx, replacementHandles)
            }
            "com.miui.powerkeeper" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(FcmLivePowerKeeperHooker, classLoader, ctx, replacementHandles)
            }
            "com.google.android.gms" -> {
                // Only the phenotype DB write runs in GMS (see QuickSharePhenotypeHooker);
                // GMS is added to the scope dynamically when the Quick Share switch is on.
                attachHooker(QuickSharePhenotypeHooker, classLoader, ctx, replacementHandles)
            }
            "com.google.android.googlequicksearchbox" -> {
                // The coordinator owns both independent Google features and one shared DexKit
                // bridge. Their callbacks still gate each feature by its own preference.
                attachHooker(GoogleAppRuntime, classLoader, ctx, replacementHandles)
            }
            "com.miui.mediaeditor" -> {
                attachHooker(MediaEditorWatermarkHooker, classLoader, ctx, replacementHandles)
            }
            "com.miui.personalassistant" -> {
                // 机型伪装: rewrites phoneModel/phoneDevice in the assistant's request environment
                // so Xiaomi's server delivers the 澎湃G1-gated "智能测算" MAML suit (精准电量).
                attachHooker(ModelSpoofHooker, classLoader, ctx, replacementHandles)
            }
            "com.android.camera" -> {
                // Runs before Camera.onCreate; the after-hook preserves model-config initialization
                // while suppressing only the APK/device mismatch exit guard when opted in.
                attachHooker(CameraDeviceMismatchHooker, classLoader, ctx, replacementHandles)
                // Register profile selection after the camera config provider initializes it.
                attachHooker(CameraLegendaryProfileHooker, classLoader, ctx, replacementHandles)
                attachHooker(CameraWatermarkHooker, classLoader, ctx, replacementHandles)
                // Must attach after CameraWatermarkHooker so its device-logo hook and the
                // camera feature hooks share the structurally resolved config facade.
                attachHooker(CameraImpersonationHooker, classLoader, ctx, replacementHandles)
                // Queue config-dependent hooks behind provider initialization and profile selection.
                attachHooker(CameraUltraQualityHooker, classLoader, ctx, replacementHandles)
            }
            "com.android.providers.downloads" -> {
                // The provider's optional Xunlei log setup creates /storage/emulated/0/.xlDownload
                // even with debug logging off. The feature only blocks that log path; downloads
                // continue through the provider's normal engine.
                attachHooker(DownloadXlLogDirectoryHooker, classLoader, ctx, replacementHandles)
            }
            "com.android.providers.downloads.ui" -> {
                // The Download Manager UI has its own package/process and must be explicitly
                // scoped; provider-side .xlDownload filtering does not reach this process.
                attachHooker(DownloadUiHooker, classLoader, ctx, replacementHandles)
            }
            "com.android.thememanager" -> {
                // Local/third-party theme apply gate. The framework-side counterpart lives in
                // system_server (ThemeDrmRevalidationHooker).
                attachHooker(ThemeManagerRightsCheckHooker, classLoader, ctx, replacementHandles)
            }
            "com.xiaomi.xmsf" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(UnlockFocusAuthHooker, classLoader, ctx, replacementHandles)
            }
            "com.xiaomi.scanner" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(PasskeyHooker, classLoader, ctx, replacementHandles)
            }
            "com.xiaomi.trustservice" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(MiTrustRiskMonitoringHooker, classLoader, ctx, replacementHandles)
            }
            "com.miui.guardprovider" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(GuardProviderEnvironmentCheckHooker, classLoader, ctx, replacementHandles)
                attachHooker(GuardProviderUploadAppListHooker, classLoader, ctx, replacementHandles)
            }
            "com.milink.service" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(MiLinkHpplayHooker, classLoader, ctx, replacementHandles)
                attachHooker(SpatialAudioBlockerHooker, classLoader, ctx, replacementHandles)
            }
            "com.xiaomi.bluetooth" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(BluetoothPluginHooker, classLoader, ctx, replacementHandles)
                attachHooker(SpatialAudioBlockerHooker, classLoader, ctx, replacementHandles)
            }
            "com.xiaomi.aicr" -> {
                attachHooker(AicrDefaultBrowserHooker, classLoader, ctx, replacementHandles)
            }
            "com.takekazex.hypertweak" -> {
                attachHooker(ModuleStatusHooker, classLoader, ctx, replacementHandles)
            }
        }
    }

    private fun attachHooker(
        hooker: BaseHooker,
        targetClassLoader: ClassLoader,
        ctx: ModuleContext,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        try {
            DebugLog.d("HookEntry", "attaching ${hooker::class.java.simpleName} package=${ctx.packageName}")
            hooker.module = this
            hooker.classLoader = targetClassLoader
            hooker.hookParam = ctx
            hooker.setHotReloadReplacementHandles(replacementHandles)

            rootHookers.add(hooker)
            hooker.performInit()
            hooker.updateParentState(true)
            hooker.setHotReloadReplacementHandles(null)
        } catch (t: Throwable) {
            hooker.setHotReloadReplacementHandles(null)
            DebugLog.e("HookEntry", "failed to attach hooker: ${hooker::class.java.simpleName}", t)
        }
    }
}
