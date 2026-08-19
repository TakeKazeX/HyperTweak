package com.takekazex.hypertweak.hook

import android.content.pm.ApplicationInfo
import android.content.Context
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadHandleStore
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.ModuleContext
import com.takekazex.hypertweak.hook.rules.systemui.AODHooker
import com.takekazex.hypertweak.hook.rules.systemui.AospSystemUiPluginBlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.ExtendUnlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.ProxyLaunchHooker
import com.takekazex.hypertweak.hook.rules.systemui.UnlockClipboardHooker
import com.takekazex.hypertweak.hook.rules.systemui.HideFingerprintIcon
import com.takekazex.hypertweak.hook.rules.systemui.HideBottomBarHooker
import com.takekazex.hypertweak.hook.rules.systemui.GestureBarActionHooker
import com.takekazex.hypertweak.hook.rules.systemui.HideLockscreenStatusBarHooker
import com.takekazex.hypertweak.hook.rules.systemui.ImmediateMonetRefreshHooker
import com.takekazex.hypertweak.hook.rules.systemui.KeyguardFingerprintAvoidHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CellularIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.WifiIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.HideCellularIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconManagerHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.IgnoreSysIconSettingsHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.StackedSignalHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CompoundIconHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.HideCarrierLabelHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.RegionSamplingHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.CellularTypeIconHooker
import com.takekazex.hypertweak.hook.rules.module.ModuleStatusHooker
import com.takekazex.hypertweak.hook.rules.module.SettingsHooker
import com.takekazex.hypertweak.hook.rules.ime.AospImeConfig
import com.takekazex.hypertweak.hook.rules.ime.AospImeHooker
import com.takekazex.hypertweak.hook.rules.ime.AospImeSystemHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.AospAppInfoEntryHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.AospAppManagerEntryHooker
import com.takekazex.hypertweak.hook.rules.system.AospPackageInstallerHooker
import com.takekazex.hypertweak.hook.rules.system.SystemConfigHooker
import com.takekazex.hypertweak.hook.rules.system.ContextualSearchSystemHooker
import com.takekazex.hypertweak.hook.rules.system.PowerButtonCtsHooker
import com.takekazex.hypertweak.hook.rules.system.VoiceInteractionServiceRepairHooker
import com.takekazex.hypertweak.hook.rules.system.PasskeyHooker
import com.takekazex.hypertweak.hook.rules.system.SpatialAudioBlockerHooker
import com.takekazex.hypertweak.hook.rules.settings.BluetoothPluginHooker
import com.takekazex.hypertweak.hook.rules.settings.SpatialAudioHooker
import com.takekazex.hypertweak.hook.rules.system.FcmLiveSystemHooker
import com.takekazex.hypertweak.hook.rules.backgesture.AospBackSystemHooker
import com.takekazex.hypertweak.hook.rules.backgesture.AospBackSystemUiHooker
import com.takekazex.hypertweak.hook.rules.backgesture.AospBackMiuiHomeHooker
import com.takekazex.hypertweak.hook.rules.systemui.SystemUIPluginHooker
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenChargingDetailHooker
import com.takekazex.hypertweak.hook.rules.systemui.glass.GlassMaterialHooker
import com.takekazex.hypertweak.hook.rules.module.RestartBroadcastHooker
import com.takekazex.hypertweak.hook.rules.powerkeeper.FcmLivePowerKeeperHooker
import com.takekazex.hypertweak.hook.rules.gms.QuickSharePhenotypeHooker
import com.takekazex.hypertweak.hook.rules.googleapp.GoogleAppLiveTranslateHooker
import com.takekazex.hypertweak.hook.rules.mediaeditor.MediaEditorWatermarkHooker
import com.takekazex.hypertweak.hook.rules.camera.CameraWatermarkHooker
import com.takekazex.hypertweak.hook.rules.camera.CameraImpersonationHooker
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.PlatformLevel
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.ApplicationAttachCallback
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import java.util.concurrent.ConcurrentHashMap

class HookEntry : XposedModule() {
    private val injectedPackages = ConcurrentHashMap.newKeySet<String>()
    private val rootHookers = ConcurrentHashMap.newKeySet<BaseHooker>()
    private val packageStates = ConcurrentHashMap<String, HotReloadPackageState>()
    private val pendingAppContextPackages = ConcurrentHashMap.newKeySet<String>()
    private lateinit var processName: String
    private var isSystemServer: Boolean = false
    private var systemServerClassLoader: ClassLoader? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        isSystemServer = param.isSystemServer
        // Initialize EzXposed with the module interface
        EzXposed.initOnModuleLoaded(this, param)
        DebugLog.setProcessTag(processName)
        DebugLog.bindXposed(this)
        initPreferences()
        DebugLog.ensureSession()
        DebugLog.d("HookEntry", "module loaded process=$processName isSystemServer=$isSystemServer")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        EzXposed.initOnSystemServerStarting(param)
        systemServerClassLoader = param.classLoader
        DebugLog.d("HookEntry", "system_server starting")
        dispatchSystemServerHookers(param.classLoader)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (!injectedPackages.add(param.packageName)) return
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
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
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
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        DebugLog.d(
            "HookEntry",
            "hot reloading old generation process=$processName packages=${packageStates.size} roots=${rootHookers.size} modes=${hotReloadModeSummary()}"
        )
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
        EzXposed.initOnModuleLoaded(this, param)
        initPreferences()
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
            restoreHookerStates(restoredState.hookerStates)
            logHotReloadHandleDiff(oldHandleIds, oldHandles)
            unhookRemainingOldHandles(oldHandles)
            return
        }

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

        restoreHookerStates(restoredState.hookerStates)

        logHotReloadHandleDiff(oldHandleIds, oldHandles)
        unhookRemainingOldHandles(oldHandles)
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
        if (packageName == "com.android.systemui") {
            ProxyLaunchHooker.register(appContext)
            ExtendUnlockHooker.syncTrustAgent(appContext)
            StackedSignalHooker.onPackageReady(appContext)
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
            if (state.packageName == "com.android.systemui") {
                ProxyLaunchHooker.register(appContext)
                ExtendUnlockHooker.syncTrustAgent(appContext)
                StackedSignalHooker.onPackageReady(appContext)
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
        try {
            val remotePrefs = getRemotePreferences(Preferences.NAME)
            Preferences.init(remotePrefs)
            DebugLog.d("HookEntry", "processName=$processName loaded remotePrefs keys=${remotePrefs.all.keys}")
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "failed to init Preferences", t)
        }
    }

    private fun isMiuiBackGestureHookEnabled(): Boolean =
        // HyperTweak: on OS4 the predictive-back Shell pipeline is broken platform-side, so
        // the AOSP back gesture is force-disabled regardless of the (hidden) preference.
        !PlatformLevel.isOs4 &&
            Preferences.getBoolean(Preferences.KEY_MIUI_BACK_GESTURE_HOOK, false)

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
        attachHooker(VoiceInteractionServiceRepairHooker, classLoader, ctx, replacementHandles)
        attachHooker(PasskeyHooker, classLoader, ctx, replacementHandles)
        attachHooker(FcmLiveSystemHooker, classLoader, ctx, replacementHandles)
        attachHooker(AospPackageInstallerHooker, classLoader, ctx, replacementHandles)
        if (AospImeConfig.isEnabled()) {
            attachHooker(AospImeSystemHooker, classLoader, ctx, replacementHandles)
        }
        if (isMiuiBackGestureHookEnabled()) {
            attachHooker(AospBackSystemHooker, classLoader, ctx, replacementHandles)
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
                attachHooker(HideFingerprintIcon, classLoader, ctx, replacementHandles)
                attachHooker(HideLockscreenStatusBarHooker, classLoader, ctx, replacementHandles)
                attachHooker(KeyguardFingerprintAvoidHooker, classLoader, ctx, replacementHandles)
                attachHooker(LockscreenChargingDetailHooker, classLoader, ctx, replacementHandles)
                attachHooker(ImmediateMonetRefreshHooker, classLoader, ctx, replacementHandles)
                attachHooker(SystemUIPluginHooker, classLoader, ctx, replacementHandles)
                attachHooker(AospSystemUiPluginBlockHooker, classLoader, ctx, replacementHandles)
                attachHooker(ExtendUnlockHooker, classLoader, ctx, replacementHandles)
                attachHooker(ProxyLaunchHooker, classLoader, ctx, replacementHandles)
                attachHooker(UnlockClipboardHooker, classLoader, ctx, replacementHandles)
                attachHooker(HideBottomBarHooker, classLoader, ctx, replacementHandles)
                attachHooker(GestureBarActionHooker, classLoader, ctx, replacementHandles)
                attachHooker(CellularIconHooker, classLoader, ctx, replacementHandles)
                attachHooker(WifiIconHooker, classLoader, ctx, replacementHandles)
                attachHooker(HideCellularIconHooker, classLoader, ctx, replacementHandles)
                attachHooker(IconManagerHooker, classLoader, ctx, replacementHandles)
                attachHooker(IgnoreSysIconSettingsHooker, classLoader, ctx, replacementHandles)
                attachHooker(StackedSignalHooker, classLoader, ctx, replacementHandles)
                attachHooker(CompoundIconHooker, classLoader, ctx, replacementHandles)
                attachHooker(HideCarrierLabelHooker, classLoader, ctx, replacementHandles)
                attachHooker(RegionSamplingHooker, classLoader, ctx, replacementHandles)
                attachHooker(CellularTypeIconHooker, classLoader, ctx, replacementHandles)
                attachHooker(GlassMaterialHooker, classLoader, ctx, replacementHandles)
                if (isMiuiBackGestureHookEnabled()) {
                    attachHooker(AospBackSystemUiHooker, classLoader, ctx, replacementHandles)
                }
            }
            "com.miui.home" -> {
                if (isMiuiBackGestureHookEnabled()) {
                    attachHooker(AospBackMiuiHomeHooker, classLoader, ctx, replacementHandles)
                }
            }
            "com.miui.aod" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(AODHooker, classLoader, ctx, replacementHandles)
            }
            "com.android.settings" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(SettingsHooker, classLoader, ctx, replacementHandles)
                attachHooker(AODHooker, classLoader, ctx, replacementHandles)
                attachHooker(PasskeyHooker, classLoader, ctx, replacementHandles)
                // The Bluetooth extension is loaded by Settings, so its UI hook must be
                // attached in this process as well as the Bluetooth service process.
                attachHooker(BluetoothPluginHooker, classLoader, ctx, replacementHandles)
                attachHooker(SpatialAudioHooker(), classLoader, ctx, replacementHandles)
            }
            "com.miui.securitycenter" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(PasskeyHooker, classLoader, ctx, replacementHandles)
                attachHooker(AospAppInfoEntryHooker, classLoader, ctx, replacementHandles)
                attachHooker(AospAppManagerEntryHooker, classLoader, ctx, replacementHandles)
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
                // Only acts while the full-screen live-translate switch is on (the package is a
                // declared required scope, see `scope.list` + `ScopeManager`); the hooker itself
                // returns early when the preference is off. See GoogleAppLiveTranslateHooker.
                attachHooker(GoogleAppLiveTranslateHooker, classLoader, ctx, replacementHandles)
            }
            "com.miui.mediaeditor" -> {
                attachHooker(MediaEditorWatermarkHooker, classLoader, ctx, replacementHandles)
            }
            "com.android.camera" -> {
                attachHooker(CameraWatermarkHooker, classLoader, ctx, replacementHandles)
                // Must attach after CameraWatermarkHooker: both touch Je.c#x(), and the
                // impersonation override needs to win (later callback) when both are on.
                attachHooker(CameraImpersonationHooker, classLoader, ctx, replacementHandles)
            }
            "com.xiaomi.scanner" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(PasskeyHooker, classLoader, ctx, replacementHandles)
            }
            "com.milink.service" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(SpatialAudioBlockerHooker, classLoader, ctx, replacementHandles)
            }
            "com.xiaomi.bluetooth" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(BluetoothPluginHooker, classLoader, ctx, replacementHandles)
                attachHooker(SpatialAudioBlockerHooker, classLoader, ctx, replacementHandles)
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
