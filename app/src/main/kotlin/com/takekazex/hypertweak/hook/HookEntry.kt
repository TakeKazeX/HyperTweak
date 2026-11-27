package com.takekazex.hypertweak.hook

import com.takekazex.hypertweak.hook.base.HostContext
import com.takekazex.hypertweak.hook.bootstrap.ProcessLifecycleHooks
import com.takekazex.hypertweak.hook.bootstrap.ProcessLogging
import com.takekazex.hypertweak.hook.bootstrap.SystemUiSettingsHost
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsStore
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * LSPosed entry (`java_init.list`). One instance is one module generation in one process. It
 * keeps the process boundaries and delegates: [ProcessSettings] owns remote preferences, live
 * settings and native publication, [PackageHookDispatcher] attaches the hook plans, and
 * [HotReloadOrchestrator] retires and adopts generations.
 */
class HookEntry : XposedModule() {
    private val state = HookProcessState()
    private val systemUiSettings = SystemUiSettingsHost(
        { state.processName }, { state.generationRetired },
        { state.packageStates["com.android.systemui"] }, { state.rootHookers }
    )
    private val settings: ProcessSettings = ProcessSettings(this, state, systemUiSettings) {
        dispatcher.refreshImeSettings()
    }
    private val dispatcher = PackageHookDispatcher(this, state, settings, systemUiSettings)
    private val hotReload = HotReloadOrchestrator(this, state, settings, dispatcher)

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        // Process boundary. The process identity is recorded unconditionally (later callbacks and
        // the debug log need it), then everything else runs behind a boundary: a module failure
        // here must be logged, not thrown back into the host's module-load path.
        state.processName = param.processName
        state.isSystemServer = param.isSystemServer
        state.skipGoogleProcess = !state.isSystemServer && ProcessLifecycleHooks.shouldSkipProcess(state.processName)
        if (state.skipGoogleProcess) return
        RuntimeSettingsStore.configure(state.processName, state.isSystemServer)
        try {
            settings.preferenceRetryGeneration.incrementAndGet()
            HostContext.reset(this)
            NativeRules.bindProcess(state.processName)
            ProcessLogging.bind()
            DebugLog.setProcessTag(state.processName)
            DebugLog.bindXposed(this)
            settings.initPreferences()
            DebugLog.ensureSession()
            DebugLog.d("HookEntry", "module loaded process=${state.processName} isSystemServer=${state.isSystemServer}")
            // Status inspection is passive; only launcher rule publication may bind the JNI bridge.
            DebugLog.d("NativeRules", NativeRules.describe())
            settings.publishNativeRuleSwitches()
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "module load handling failed", t)
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        state.systemServerClassLoader = param.classLoader
        DebugLog.d("HookEntry", "system_server starting")
        // Safety net: a structural failure while dispatching the framework hooks (any hooker failing
        // to attach, or the dispatch itself blowing up) must land in the in-app debug log so it can
        // be diagnosed from the app's Logs page without digging through LSPosed's daemon files.
        // Each attachHooker already isolates per-hooker failures; this catches anything that escapes
        // it (e.g. an unguarded preference read) and records it, then rethrows so the host still
        // sees the failure.
        try {
            dispatcher.dispatchSystemServerHookers(param.classLoader)
            settings.scheduleContextRetry()
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "system_server hook dispatch failed", t)
            throw t
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (state.skipGoogleProcess) return
        if (!state.injectedPackages.add(param.packageName)) return
        // Process boundary: a failure while installing this package's hooks must be isolated and
        // logged rather than escaping into the host app's own package-load path.
        try {
            state.recordPackageState(
                packageName = param.packageName,
                classLoader = param.defaultClassLoader,
                appInfo = param.applicationInfo,
                isFirstPackage = param.isFirstPackage,
                isPackageReady = false,
                appContext = null
            )
            DebugLog.d(
                "HookEntry",
                "package loaded package=${param.packageName} process=${state.processName} first=${param.isFirstPackage}"
            )

            dispatcher.dispatchPackageHookers(
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
        if (state.skipGoogleProcess) return
        // Process boundary, same rationale as onPackageLoaded.
        try {
            state.recordPackageState(
                packageName = param.packageName,
                classLoader = param.classLoader,
                appInfo = param.applicationInfo,
                isFirstPackage = false,
                isPackageReady = true,
                appContext = HostContext.appContextOrNull,
                pluginStates = dispatcher.currentPluginStates(param.packageName)
            )

            dispatcher.handlePackageReadyContext(param.packageName, param.classLoader)
            if (state.processName == ProcessSettings.LAUNCHER_PACKAGE) {
                // The early daemon binding may have failed before package readiness.
                if (!settings.hasPreferencesSource) settings.tryInitPreferences()
                settings.publishNativeRuleSwitches()
            }

            if (param.packageName == "com.android.systemui") {
                val appContext = state.packageStates[param.packageName]?.appContext
                    ?: HostContext.appContextOrNull
                ProcessLifecycleHooks.onSystemUiPackageReady(appContext, param.classLoader)
            }
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "package ready handling failed package=${param.packageName}", t)
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean = hotReload.retire(param)

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) = hotReload.adopt(param)
}
