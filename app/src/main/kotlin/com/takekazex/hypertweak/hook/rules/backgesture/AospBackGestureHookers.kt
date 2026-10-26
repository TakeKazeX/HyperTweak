@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.backgesture

import android.content.Context
import android.os.Build
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.backgesture.hooks.core.HookRuntimeCore
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.util.platform.UpstreamBackGesture
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/** One upstream runtime per injected process; hook handles remain owned by BaseHooker. */
private object BackGestureRuntimeProvider {
    val runtime = BackGestureRuntime()
}

private fun BaseHooker.backGestureRegistrar() = object : HookRuntimeCore.HookRegistrar {
    override fun register(method: Method, id: String, hooker: XposedInterface.Hooker) =
        registerRuntimeHook(method, "backgesture:$id") { chain ->
            val lifecycle = id.startsWith("systemui_edge_back_") ||
                id.startsWith("systemui_navigation_bar_controller_") ||
                id.startsWith("systemui_contextual_search_nav_") ||
                id == "server_back_navigation_done_cleanup"
            if (!UpstreamBackGesture.installed &&
                (lifecycle || BackGestureRuntimeProvider.runtime.shouldRunBackGestureHooks())) {
                hooker.intercept(chain)
            } else chain.proceed()
        }

    override fun deoptimize(method: Method) = this@backGestureRegistrar.deoptimize(method)

    override fun getInvoker(method: Method): XposedInterface.Invoker<*, Method> =
        module.getInvoker(method)
}

object AospBackSystemUiHooker : StaticHooker() {
    override fun onHook() {
        if (Build.VERSION.SDK_INT < 37) return
        BackGestureRuntimeProvider.runtime.installSystemUiHooks(classLoader, backGestureRegistrar())
    }

    fun refreshPreferences() {
        if (isHooked) BackGestureRuntimeProvider.runtime.refreshBackGesturePreferences()
    }

    fun setOverviewVisibilityListener(listener: java.util.function.Consumer<Boolean?>?) {
        if (Build.VERSION.SDK_INT >= 37 && isHooked) {
            BackGestureRuntimeProvider.runtime.setOverviewVisibilityListener(listener)
        }
    }

    fun clearLauncherOverviewVisibility() {
        if (Build.VERSION.SDK_INT >= 37 && isHooked) {
            BackGestureRuntimeProvider.runtime.clearLauncherOverviewVisibility()
        }
    }

    /**
     * Registers the launcher Overview bridge once a SystemUI application context exists.
     *
     * This deliberately does not require [isHooked]: the context becomes available during
     * package-ready handling, which runs before the root hookers are attached for this process.
     * Gating on [isHooked] therefore skipped the registration on every start, which is exactly
     * the "zero registrations" the device logs showed. The runtime object is a process-wide
     * singleton and tolerates the call arriving before its hooks are installed; the receiver is
     * independent of them.
     */
    fun onApplicationContextReady(context: Context?) {
        if (Build.VERSION.SDK_INT < 37 || context == null) return
        UpstreamBackGesture.refresh(context)
        BackGestureRuntimeProvider.runtime.onApplicationContextReady(context)
    }

    /** Called before HyperTweak begins saving or retiring any process-owned state. */
    fun checkHotReloadReady() {
        if (isHooked) BackGestureRuntimeProvider.runtime.checkHotReloadReady()
    }

    override fun saveHotReloadState(): Any? =
        if (isHooked) BackGestureRuntimeProvider.runtime.saveHotReloadState() else null

    override fun restoreHotReloadState(state: Any?) {
        if (isHooked) {
            BackGestureRuntimeProvider.runtime.restoreHotReloadState(state)
            DebugLog.i("BackGesture", "hot reloaded process=com.android.systemui; host restoration queued")
        }
    }
}

object AospBackSystemHooker : StaticHooker() {
    override fun onHook() {
        if (Build.VERSION.SDK_INT < 37) return
        BackGestureRuntimeProvider.runtime.installSystemServerHooks(classLoader, backGestureRegistrar())
    }
}
