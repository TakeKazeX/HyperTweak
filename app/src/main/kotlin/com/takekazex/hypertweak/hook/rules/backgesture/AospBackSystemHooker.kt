package com.takekazex.hypertweak.hook.rules.backgesture

import com.takekazex.hypertweak.hook.base.StaticHooker

object AospBackSystemHooker : StaticHooker() {
    private val runtime get() = AospBackGestureRuntimeProvider.runtime

    override fun onHook() {
        runtime.installSystemServerHooks(classLoader, aospBackRegistrar())
    }

    override fun saveHotReloadState(): Any? = runtime.saveHotReloadState()

    override fun onPrepareHotReload() {
        runtime.prepareHotReload()
    }

    override fun restoreHotReloadState(state: Any?) {
        runtime.restoreHotReloadState(state)
    }
}
