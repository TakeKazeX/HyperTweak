package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.base.BaseHooker

/** A typed factory exposes target identity without initializing host singletons in plan tests. */
internal sealed interface HookStep {
    class Install<T : BaseHooker>(val target: Class<T>, val create: () -> T) : HookStep
    data object Icons : HookStep
}

internal inline fun <reified T : BaseHooker> MutableList<HookStep>.hook(noinline create: () -> T) {
    add(HookStep.Install(T::class.java, create))
}
