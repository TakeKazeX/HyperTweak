package com.takekazex.hypertweak.hook.rules.systemui.island.hooks

import com.takekazex.hypertweak.hook.base.BaseHooker
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method

/** Parameter view shared by before/after binders. */
internal class HookParam(
    val thisObject: Any,
    val args: Array<Any?>,
    initialResult: Any? = null,
    private val after: Boolean = false,
) {
    var result: Any? = initialResult
        set(value) {
            field = value
            if (!after) overridden = true
        }

    internal var overridden = false
}

/** One place for stable hook ids and before/after adapters. */
internal class IslandHookRegistrar(private val owner: BaseHooker) {
    fun deoptimize(executable: Executable): Boolean = owner.deoptimize(executable)

    fun runtime(method: Method, id: String, hooker: XposedInterface.Hooker) =
        owner.registerRuntimeHook(method, id, hooker)

    fun before(method: Method, id: String, block: (HookParam) -> Unit) {
        owner.registerRuntimeHook(method, id, XposedInterface.Hooker { chain ->
            val self = chain.thisObject ?: return@Hooker chain.proceed()
            val args = chain.args.toTypedArray()
            val param = HookParam(self, args)
            block(param)
            if (param.overridden) param.result else chain.proceed(args)
        })
    }

    fun after(method: Method, id: String, block: (HookParam) -> Unit) {
        owner.registerRuntimeHook(method, id, XposedInterface.Hooker { chain ->
            val self = chain.thisObject
            val result = chain.proceed()
            if (self == null) return@Hooker result
            val param = HookParam(self, chain.args.toTypedArray(), result, after = true)
            block(param)
            param.result
        })
    }
}
