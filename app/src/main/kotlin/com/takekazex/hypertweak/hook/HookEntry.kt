package com.takekazex.hypertweak.hook

import android.content.pm.ApplicationInfo
import android.content.Context
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HotReloadHandleStore
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.ModuleContext
import com.takekazex.hypertweak.hook.rules.systemui.AODHooker
import com.takekazex.hypertweak.hook.rules.systemui.HideFingerprintIcon
import com.takekazex.hypertweak.hook.rules.systemui.HideBottomBarHooker
import com.takekazex.hypertweak.hook.rules.module.ModuleStatusHooker
import com.takekazex.hypertweak.hook.rules.module.SettingsHooker
import com.takekazex.hypertweak.hook.rules.system.SystemConfigHooker
import com.takekazex.hypertweak.hook.rules.system.PasskeyHooker
import com.takekazex.hypertweak.hook.rules.system.SpatialAudioBlockerHooker
import com.takekazex.hypertweak.hook.rules.systemui.SystemUIPluginHooker
import com.takekazex.hypertweak.hook.rules.module.RestartBroadcastHooker
import com.takekazex.hypertweak.hook.rules.settings.BluetoothPluginHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import java.util.concurrent.ConcurrentHashMap

class HookEntry : XposedModule() {
    private val injectedPackages = ConcurrentHashMap.newKeySet<String>()
    private val rootHookers = ConcurrentHashMap.newKeySet<BaseHooker>()
    private val packageStates = ConcurrentHashMap<String, HotReloadPackageState>()
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

        val appContext = packageStates[param.packageName]?.appContext
        if (appContext != null) {
            Preferences.initLocalCache(appContext)
            RestartBroadcastHooker.register(appContext)
            DebugLog.d("HookEntry", "package ready package=${param.packageName} context=${appContext.packageName}")
        } else {
            DebugLog.w("HookEntry", "package ready package=${param.packageName} without app context")
        }

        if (param.packageName == "com.android.systemui") {
            HideBottomBarHooker.onPackageReady(appContext, param.classLoader)
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        DebugLog.d(
            "HookEntry",
            "hot reloading old generation process=$processName packages=${packageStates.size} roots=${rootHookers.size} modes=${hotReloadModeSummary()}"
        )
        val ready = runCatching {
            refreshHotReloadSnapshots()
            param.setSavedInstanceState(
                HotReloadState.save(
                    processName = processName,
                    isSystemServer = isSystemServer,
                    systemServerClassLoader = systemServerClassLoader,
                    packages = packageStates.values
                )
            )
            if (!DexKitManager.prepareForHotReload()) {
                error("DexKit native bridge users are still active")
            }
            rootHookers.forEach { it.prepareForHotReload() }
            rootHookers.forEach { it.resetAfterHotReloadPrepared() }
            rootHookers.clear()
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
        attachHooker(PasskeyHooker, classLoader, ctx, replacementHandles)
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

        when (packageName) {
            "com.android.systemui" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(AODHooker, classLoader, ctx, replacementHandles)
                attachHooker(HideFingerprintIcon, classLoader, ctx, replacementHandles)
                attachHooker(SystemUIPluginHooker, classLoader, ctx, replacementHandles)
                attachHooker(HideBottomBarHooker, classLoader, ctx, replacementHandles)
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
                attachHooker(BluetoothPluginHooker, classLoader, ctx, replacementHandles)
            }
            "com.miui.securitycenter" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx, replacementHandles)
                attachHooker(PasskeyHooker, classLoader, ctx, replacementHandles)
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
