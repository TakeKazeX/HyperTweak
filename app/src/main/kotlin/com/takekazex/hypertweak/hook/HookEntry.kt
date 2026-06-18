package com.takekazex.hypertweak.hook

import android.content.pm.ApplicationInfo
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.DexKitManager
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
        DebugLog.bindXposed(this)
        initPreferences()
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
            isPackageReady = false
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
            isPackageReady = true
        )

        val appContext = runCatching { EzXposed.appContextOrNull }.getOrNull()
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
            rootHookers.clear()
            DebugLog.d("HookEntry", "hot reload preparation completed; old generation can retire")
            DebugLog.prepareForHotReload()
        }.onFailure { t ->
            DexKitManager.cancelHotReloadPreparation()
            DebugLog.e("HookEntry", "hot reload preparation failed; keeping old generation active", t)
        }.isSuccess
        return ready
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        processName = param.processName
        isSystemServer = param.isSystemServer
        EzXposed.initOnModuleLoaded(this, param)
        initPreferences()
        val restoredState = HotReloadState.restore(param.savedInstanceState)
        val oldHandleIds = param.oldHookHandles.mapNotNull { it.id }.toSet()
        DebugLog.d(
            "HookEntry",
            "hot reloaded process=$processName packages=${restoredState?.packages?.map { it.packageName }} oldHandles=${param.oldHookHandles.size} oldIds=${oldHandleIds.size}"
        )

        var unhookedCount = 0
        var unhookFailedCount = 0
        param.oldHookHandles.forEach { handle ->
            runCatching {
                handle.unhook()
                unhookedCount++
            }.onFailure {
                unhookFailedCount++
                DebugLog.w("HookEntry", "failed to unhook old handle ${handle.id}", it)
            }
        }
        DebugLog.d(
            "HookEntry",
            "hot reload removed old handles ok=$unhookedCount failed=$unhookFailedCount"
        )

        injectedPackages.clear()
        rootHookers.clear()
        packageStates.clear()

        if (restoredState == null) {
            DebugLog.w("HookEntry", "hot reloaded without restorable target state")
            return
        }

        processName = restoredState.processName
        isSystemServer = restoredState.isSystemServer
        systemServerClassLoader = restoredState.systemServerClassLoader

        if (restoredState.isSystemServer) {
            val targetClassLoader = restoredState.systemServerClassLoader ?: run {
                DebugLog.w("HookEntry", "hot reloaded system_server without classLoader")
                return
            }
            EzReflect.init(targetClassLoader)
            dispatchSystemServerHookers(targetClassLoader)
            return
        }

        restoredState.packages.forEach { state ->
            recordPackageState(
                packageName = state.packageName,
                classLoader = state.classLoader,
                appInfo = state.appInfo,
                isFirstPackage = state.isFirstPackage,
                isPackageReady = state.isPackageReady
            )
            injectedPackages.add(state.packageName)
            EzReflect.init(state.classLoader)
            dispatchPackageHookers(
                packageName = state.packageName,
                classLoader = state.classLoader,
                appInfo = state.appInfo,
                isFirstPackage = false
            )
            if (state.isPackageReady) {
                onRestoredPackageReady(state)
            }
        }

        val newHandleIds = rootHookers.flatMap { it.collectManagedHookHandles() }
            .mapNotNull { it.id }
            .toSet()
        DebugLog.d(
            "HookEntry",
            "hot reload registered new handles=${newHandleIds.size} matched=${newHandleIds.intersect(oldHandleIds).size} added=${newHandleIds.minus(oldHandleIds).size} removed=${oldHandleIds.minus(newHandleIds).size}"
        )
    }

    private fun recordPackageState(
        packageName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo?,
        isFirstPackage: Boolean,
        isPackageReady: Boolean
    ) {
        val old = packageStates[packageName]
        packageStates[packageName] = HotReloadPackageState(
            packageName = packageName,
            processName = processName,
            classLoader = classLoader,
            appInfo = appInfo ?: old?.appInfo,
            isFirstPackage = old?.isFirstPackage ?: isFirstPackage,
            isPackageReady = old?.isPackageReady == true || isPackageReady
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

    private fun onRestoredPackageReady(state: HotReloadPackageState) {
        val appContext = runCatching { EzXposed.appContextOrNull }.getOrNull()
        if (appContext != null) {
            Preferences.initLocalCache(appContext)
            RestartBroadcastHooker.register(appContext)
        }

        if (state.packageName == "com.android.systemui") {
            HideBottomBarHooker.onPackageReady(appContext, state.classLoader)
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

    private fun dispatchSystemServerHookers(classLoader: ClassLoader) {
        val ctx = ModuleContext(
            processName = processName,
            packageName = "system",
            isSystemServer = true
        )
        attachHooker(SystemConfigHooker, classLoader, ctx)
        attachHooker(PasskeyHooker, classLoader, ctx)
    }

    private fun dispatchPackageHookers(
        packageName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo?,
        isFirstPackage: Boolean
    ) {
        val ctx = ModuleContext(
            processName = processName,
            packageName = packageName,
            isSystemServer = isSystemServer,
            isFirstPackage = isFirstPackage,
            isPackageReady = false,
            appInfo = appInfo
        )

        when (packageName) {
            "com.android.systemui" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx)
                attachHooker(AODHooker, classLoader, ctx)
                attachHooker(HideFingerprintIcon, classLoader, ctx)
                attachHooker(SystemUIPluginHooker, classLoader, ctx)
                attachHooker(HideBottomBarHooker, classLoader, ctx)
            }
            "com.miui.aod" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx)
                attachHooker(AODHooker, classLoader, ctx)
            }
            "com.android.settings" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx)
                attachHooker(SettingsHooker, classLoader, ctx)
                attachHooker(AODHooker, classLoader, ctx)
                attachHooker(PasskeyHooker, classLoader, ctx)
                attachHooker(BluetoothPluginHooker, classLoader, ctx)
            }
            "com.miui.securitycenter" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx)
                attachHooker(PasskeyHooker, classLoader, ctx)
            }
            "com.xiaomi.scanner" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx)
                attachHooker(PasskeyHooker, classLoader, ctx)
            }
            "com.milink.service" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx)
                attachHooker(SpatialAudioBlockerHooker, classLoader, ctx)
            }
            "com.xiaomi.bluetooth" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx)
                attachHooker(SpatialAudioBlockerHooker, classLoader, ctx)
            }
            "com.takekazex.hypertweak" -> {
                attachHooker(ModuleStatusHooker, classLoader, ctx)
            }
        }
    }

    private fun attachHooker(
        hooker: BaseHooker,
        targetClassLoader: ClassLoader,
        ctx: ModuleContext
    ) {
        try {
            DebugLog.d("HookEntry", "attaching ${hooker::class.java.simpleName} package=${ctx.packageName}")
            hooker.module = this
            hooker.classLoader = targetClassLoader
            hooker.hookParam = ctx

            rootHookers.add(hooker)
            hooker.performInit()
            hooker.updateParentState(true)
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "failed to attach hooker: ${hooker::class.java.simpleName}", t)
        }
    }
}
