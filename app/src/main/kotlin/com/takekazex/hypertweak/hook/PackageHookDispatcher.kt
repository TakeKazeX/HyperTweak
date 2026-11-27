package com.takekazex.hypertweak.hook

import android.content.Context
import android.content.pm.ApplicationInfo
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HostContext
import com.takekazex.hypertweak.hook.base.HotReloadHandleStore
import com.takekazex.hypertweak.hook.base.ModuleContext
import com.takekazex.hypertweak.hook.bootstrap.PackageHookPlan
import com.takekazex.hypertweak.hook.bootstrap.ProcessLifecycleHooks
import com.takekazex.hypertweak.hook.bootstrap.SystemServerHookPlan
import com.takekazex.hypertweak.hook.bootstrap.SystemUiSettingsHost
import com.takekazex.hypertweak.hook.runtime.reload.HotReloadPackageState
import com.takekazex.hypertweak.hook.runtime.reload.HotReloadPluginState
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedModule

/**
 * Attaches the hook plans to a process and publishes package-ready host contexts. Cold startup
 * and hot reload go through the same paths; hot reload passes the carried old handles.
 */
internal class PackageHookDispatcher(
    private val module: XposedModule,
    private val state: HookProcessState,
    private val settings: ProcessSettings,
    private val systemUiSettings: SystemUiSettingsHost
) {
    private val processName get() = state.processName

    fun handlePackageReadyContext(packageName: String, classLoader: ClassLoader) {
        val appContext = state.packageStates[packageName]?.appContext ?: HostContext.appContextOrNull?.also { context ->
            state.recordPackageState(
                packageName = packageName,
                classLoader = classLoader,
                appInfo = state.packageStates[packageName]?.appInfo,
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
        if (!state.pendingAppContextPackages.add(packageName)) return

        HostContext.runOnApplicationAttach { context ->
                val appContext = context.applicationContext ?: context
                if (appContext.packageName != packageName) return@runOnApplicationAttach
                if (!state.pendingAppContextPackages.remove(packageName)) return@runOnApplicationAttach

                state.recordPackageState(
                    packageName = packageName,
                    classLoader = classLoader,
                    appInfo = state.packageStates[packageName]?.appInfo,
                    isFirstPackage = false,
                    isPackageReady = true,
                    appContext = appContext,
                    pluginStates = currentPluginStates(packageName)
                )
                onPackageReadyContextAvailable(packageName, appContext)
        }
    }

    private fun onPackageReadyContextAvailable(packageName: String, appContext: Context) {
        Preferences.initLocalCache(appContext)
        ProcessLifecycleHooks.registerRestartReceiver(appContext)
        settings.attachContext(appContext)
        ProcessLifecycleHooks.onPackageContextReady(processName, packageName, appContext)
        if (packageName == "com.android.systemui") {
            onSystemUiContextReady(appContext)
        }
        DebugLog.d("HookEntry", "package ready package=$packageName context=${appContext.packageName}")
    }

    /** Cold startup and hot reload must publish the same host environment to every consumer. */
    private fun onSystemUiContextReady(context: Context) {
        ProcessLifecycleHooks.beforeSystemUiContextAttached()
        settings.attachNativePublisher(context)
        settings.attachContext(context)
        ProcessLifecycleHooks.onSystemUiContextReady(context)
    }

    fun onRestoredPackageReady(
        packageState: HotReloadPackageState,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        val appContext = packageState.appContext ?: HostContext.appContextOrNull
        if (appContext != null) {
            Preferences.initLocalCache(appContext)
            ProcessLifecycleHooks.registerRestartReceiver(appContext)
            settings.attachContext(appContext)
            ProcessLifecycleHooks.onPackageContextReady(processName, packageState.packageName, appContext)
            if (packageState.packageName == "com.android.systemui") {
                onSystemUiContextReady(appContext)
            }
            DebugLog.d("HookEntry", "restored package ready package=${packageState.packageName} context=${appContext.packageName}")
        } else {
            DebugLog.w("HookEntry", "restored package ready package=${packageState.packageName} without app context")
        }

        if (packageState.packageName == "com.android.systemui") {
            ProcessLifecycleHooks.restoreSystemUiPackage(appContext, packageState.classLoader, packageState.pluginStates, replacementHandles)
        }
    }

    fun currentPluginStates(packageName: String): List<HotReloadPluginState> {
        return if (packageName == "com.android.systemui") {
            ProcessLifecycleHooks.snapshotSystemUiPlugins()
        } else {
            emptyList()
        }
    }

    fun dispatchSystemServerHookers(
        classLoader: ClassLoader,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        val ctx = ModuleContext(
            processName = processName,
            packageName = "system",
            isSystemServer = true,
            appContext = null
        )
        SystemServerHookPlan.attach { hooker ->
            attachHooker(hooker, classLoader, ctx, replacementHandles)
        }
    }

    fun dispatchPackageHookers(
        packageName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo?,
        isFirstPackage: Boolean,
        isPackageReady: Boolean = false,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        val ctx = ModuleContext(
            processName = processName,
            packageName = packageName,
            isSystemServer = state.isSystemServer,
            isFirstPackage = isFirstPackage,
            isPackageReady = isPackageReady,
            appInfo = appInfo,
            appContext = null
        )

        com.takekazex.hypertweak.hook.base.dex.DexRuntime.registerSource(appInfo)
        // One lazy DexKit scope per package dispatch: every consumer below shares one bridge.
        val sharedSession = appInfo?.sourceDir?.takeIf { it.isNotBlank() }
            ?.let(com.takekazex.hypertweak.hook.base.dex.DexRuntime::beginSession)
        try {

        // The icon ports are keyed by the module instance; retirement releases the same owner.
        if (packageName == "com.android.systemui") com.takekazex.hypertweak.hook.bootstrap.SystemUiIconPorts.bind(module)
        PackageHookPlan.attach(ctx, installIcons = {
            systemUiSettings.iconHookers().forEach { attachHooker(it, classLoader, ctx, replacementHandles) }
            // Compare the first full boot snapshot with the values used at installation.
            systemUiSettings.recordInstalledIcons()
        }) { hooker ->
            attachHooker(hooker, classLoader, ctx, replacementHandles)
        }
        } finally {
            sharedSession?.close()
        }
    }

    /** A scoped process may have started before the keyboard was selected. */
    fun refreshImeSettings(): Boolean {
        val ime = ProcessLifecycleHooks.imeHooker
        if (ime !in state.rootHookers) {
            val packageState = state.packageStates.values.firstOrNull {
                ProcessLifecycleHooks.isImePackage(it.packageName, processName)
            } ?: return true
            attachHooker(ime, packageState.classLoader, ModuleContext(
                processName, packageState.packageName, false, packageState.isFirstPackage,
                packageState.isPackageReady, packageState.appInfo, packageState.appContext
            ))
        }
        return ProcessLifecycleHooks.refreshImeSettings()
    }

    private fun attachHooker(
        hooker: BaseHooker,
        targetClassLoader: ClassLoader,
        ctx: ModuleContext,
        replacementHandles: HotReloadHandleStore? = null
    ) {
        try {
            DebugLog.d("HookEntry", "attaching ${hooker::class.java.simpleName} package=${ctx.packageName}")
            hooker.module = module
            hooker.classLoader = targetClassLoader
            hooker.hookParam = ctx
            hooker.setHotReloadReplacementHandles(replacementHandles)

            state.rootHookers.add(hooker)
            hooker.performInit()
            hooker.updateParentState(true)
            hooker.setHotReloadReplacementHandles(null)
        } catch (t: Throwable) {
            hooker.setHotReloadReplacementHandles(null)
            DebugLog.e("HookEntry", "failed to attach hooker: ${hooker::class.java.simpleName}", t)
        }
    }
}
