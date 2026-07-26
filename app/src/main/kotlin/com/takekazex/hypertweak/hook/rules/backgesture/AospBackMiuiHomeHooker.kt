package com.takekazex.hypertweak.hook.rules.backgesture

import com.takekazex.hypertweak.hook.base.StaticHooker

/**
 * Launcher-side hooks. The runtime decides whether the route may be installed at all, since only
 * Launcher 7 and older expose the `com.miui.home` classes it needs.
 */
object AospBackMiuiHomeHooker : StaticHooker() {
    private val runtime get() = AospBackGestureRuntimeProvider.runtime

    override fun onHook() {
        runtime.installMiuiHomeHooks(classLoader, aospBackRegistrar())
    }

    override fun saveHotReloadState(): Any? = runtime.saveHotReloadState()

    override fun onPrepareHotReload() {
        runtime.prepareHotReload()
    }

    override fun restoreHotReloadState(state: Any?) {
        runtime.restoreHotReloadState(state)
    }
}
