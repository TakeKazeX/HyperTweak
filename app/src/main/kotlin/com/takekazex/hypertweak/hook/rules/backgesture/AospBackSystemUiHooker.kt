package com.takekazex.hypertweak.hook.rules.backgesture

import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.backgesture.AospBackGestureRuntime
import com.takekazex.hypertweak.hook.rules.backgesture.AospBackGestureRuntimeProvider

object AospBackSystemUiHooker : StaticHooker() {
    private val runtime get() = AospBackGestureRuntimeProvider.runtime

    override fun onHook() {
        runtime.installSystemUiHooks(classLoader, registrar())
    }

    override fun saveHotReloadState(): Any? = runtime.saveHotReloadState()

    override fun onPrepareHotReload() {
        runtime.prepareHotReload()
    }

    override fun restoreHotReloadState(state: Any?) {
        runtime.restoreHotReloadState(state)
    }

    private fun registrar() = AospBackGestureRuntime.HookRegistrar { method, id, hooker ->
        registerRuntimeHook(method, id, hooker)
    }
}
