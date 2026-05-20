package com.takekazex.hypertweak.hook.base

import io.github.libxposed.api.XposedInterface

@JvmInline
value class HookResult internal constructor(val value: Any?)

interface HookScope : XposedInterface.Chain {
    fun result(value: Any?): HookResult = HookResult(value)
}
