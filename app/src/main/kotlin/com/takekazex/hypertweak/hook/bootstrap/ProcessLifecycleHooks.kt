package com.takekazex.hypertweak.hook.bootstrap

import android.content.Context
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HotReloadHandleStore
import com.takekazex.hypertweak.hook.rules.backgesture.AospBackSystemUiHooker
import com.takekazex.hypertweak.hook.rules.googleapp.GoogleAppProcessPolicy
import com.takekazex.hypertweak.hook.rules.googleapp.GoogleAppRuntime
import com.takekazex.hypertweak.hook.rules.ime.AospImeConfig
import com.takekazex.hypertweak.hook.rules.ime.AospImeHooker
import com.takekazex.hypertweak.hook.rules.module.RestartBroadcastHooker
import com.takekazex.hypertweak.hook.rules.securitycenter.battery.BatteryInfoHooker
import com.takekazex.hypertweak.hook.rules.system.BatteryAutoPowerSaveHooker
import com.takekazex.hypertweak.hook.rules.systemui.ExtendUnlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.ProxyLaunchHooker
import com.takekazex.hypertweak.hook.rules.systemui.SystemUIPluginHooker
import com.takekazex.hypertweak.hook.rules.systemui.aod.AODHooker
import com.takekazex.hypertweak.hook.rules.systemui.gesturebar.HideBottomBarHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.StackedSignalHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHotReloadRecovery
import com.takekazex.hypertweak.hook.rules.systemui.icon.carrier.ControlCenterCarrierBlockHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.left.LeftContainerHooker
import com.takekazex.hypertweak.hook.rules.systemui.lockscreen.HideLockscreenStatusBarHooker
import com.takekazex.hypertweak.hook.runtime.reload.HotReloadPluginState
import com.takekazex.hypertweak.util.logging.DebugLog
import io.github.libxposed.api.XposedInterface

/**
 * Feature callbacks that `HookEntry` forwards at process lifecycle boundaries.
 *
 * `HookEntry` keeps process generations, loaders and handles; this object owns which concrete
 * hookers participate at each boundary and their order, so the entry point imports no feature.
 */
internal object ProcessLifecycleHooks {
    // Google app
    fun shouldSkipProcess(processName: String): Boolean = GoogleAppProcessPolicy.shouldSkipProcess(processName)

    fun isGoogleProcess(processName: String, packageNames: Collection<String>): Boolean =
        processName == GoogleAppRuntime.PACKAGE ||
            processName.startsWith("${GoogleAppRuntime.PACKAGE}:") ||
            GoogleAppRuntime.PACKAGE in packageNames

    fun replaceCarriedGoogleHandle(handle: XposedInterface.HookHandle): Boolean =
        GoogleAppRuntime.replaceOldHandle(handle)

    // Every package with an application context
    fun registerRestartReceiver(appContext: Context) = RestartBroadcastHooker.register(appContext)

    fun onPackageContextReady(processName: String, packageName: String, appContext: Context) {
        if (packageName == BatteryInfoHooker.PACKAGE && processName == packageName) {
            BatteryInfoHooker.onPackageReady(appContext)
        }
    }

    // SystemUI
    fun onSystemUiPackageReady(appContext: Context?, classLoader: ClassLoader) {
        HideBottomBarHooker.onPackageReady(appContext, classLoader)
        // The launcher Overview bridge needs an application context, which package load
        // does not have yet. Without this the authoritative recent-tasks exit signal is
        // never registered and the gesture bar falls back to a polling watchdog.
        AospBackSystemUiHooker.onApplicationContextReady(appContext)
    }

    /** Runs before the native publisher and runtime settings attach to the SystemUI context. */
    fun beforeSystemUiContextAttached() = AODHooker.refreshSettings()

    /** Cold startup and hot reload publish the same host environment to every consumer. */
    fun onSystemUiContextReady(context: Context) {
        ProxyLaunchHooker.register(context)
        ExtendUnlockHooker.syncTrustAgent(context)
        StackedSignalHooker.onPackageReady(context)
        ControlCenterCarrierBlockHooker.onPackageReady(context)
        LeftContainerHooker.onPackageReady(context)
        // The battery monitor needs a Context to register its receiver; appContext is null while
        // hooks are attached at package load.
        BatteryAutoPowerSaveHooker.onPackageReady(context)
        // The launcher Overview bridge needs an application context, and this is the path that
        // actually runs for SystemUI: package load has no context, so the earlier registration
        // attempt inside installSystemUiHooks only logs and defers. Without this call the module
        // never receives the authoritative Overview enter/exit, and recent-task hiding degrades to
        // guessing from timers. Device logs on 2026-10-20 confirmed zero registrations.
        AospBackSystemUiHooker.onApplicationContextReady(context)
        // Readiness signal for the keyguard status bar discovery fallback; the hooker never treats
        // the context as a View.
        HideLockscreenStatusBarHooker.onApplicationContextReady(context)
    }

    /** Restores package-ready hooks of a carried SystemUI package into the replacement handles. */
    fun restoreSystemUiPackage(
        appContext: Context?,
        classLoader: ClassLoader,
        pluginStates: List<HotReloadPluginState>,
        replacementHandles: HotReloadHandleStore?
    ) {
        HideBottomBarHooker.setHotReloadReplacementHandles(replacementHandles)
        runCatching {
            HideBottomBarHooker.onPackageReady(appContext, classLoader)
        }.also {
            HideBottomBarHooker.setHotReloadReplacementHandles(null)
        }.onFailure { t ->
            DebugLog.e("HookEntry", "failed to restore SystemUI package ready hooks", t)
        }
        runCatching { AospBackSystemUiHooker.onApplicationContextReady(appContext) }
            .onFailure { t -> DebugLog.e("HookEntry", "failed to restore the launcher overview bridge", t) }
        SystemUIPluginHooker.setHotReloadReplacementHandles(replacementHandles)
        runCatching {
            SystemUIPluginHooker.restoreHotReloadPlugins(pluginStates)
        }.also {
            SystemUIPluginHooker.setHotReloadReplacementHandles(null)
        }.onFailure { t ->
            DebugLog.e("HookEntry", "failed to restore SystemUI plugin hooks", t)
        }
    }

    fun snapshotSystemUiPlugins(): List<HotReloadPluginState> = SystemUIPluginHooker.snapshotHotReloadPlugins()

    // Hot reload
    fun checkHotReloadReady() = AospBackSystemUiHooker.checkHotReloadReady()

    fun cancelSystemUiRecovery() = StatusIconHotReloadRecovery.cancel()

    fun scheduleSystemUiRecovery(context: Context, roots: Collection<BaseHooker>) =
        StatusIconHotReloadRecovery.schedule(context, roots)

    // IME processes selected after startup
    val imeHooker: BaseHooker get() = AospImeHooker

    fun isImePackage(packageName: String): Boolean = AospImeConfig.shouldHookImePackage(packageName)

    fun refreshImeSettings(): Boolean = AospImeHooker.refreshSettings()
}
