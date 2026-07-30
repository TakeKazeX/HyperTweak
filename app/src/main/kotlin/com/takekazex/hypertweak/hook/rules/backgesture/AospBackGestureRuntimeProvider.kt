package com.takekazex.hypertweak.hook.rules.backgesture

import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.rules.backgesture.hooks.core.HookRuntimeCore
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

internal object AospBackGestureRuntimeProvider {
    val runtime = AospBackGestureRuntime()
}

/**
 * Binds the vendored runtime's hook installation back onto this hooker, so BaseHooker keeps
 * ownership of every handle it registers (and can replace them by id during hot reload).
 */
internal fun BaseHooker.aospBackRegistrar(): HookRuntimeCore.HookRegistrar =
    object : HookRuntimeCore.HookRegistrar {
        override fun register(
            method: Method,
            hookId: String,
            hooker: XposedInterface.Hooker
        ): XposedInterface.HookHandle = registerRuntimeHook(method, hookId, hooker)

        override fun deoptimize(method: Method): Boolean = module.deoptimize(method)

        override fun getInvoker(method: Method): XposedInterface.Invoker<*, Method> =
            module.getInvoker(method)
    }
