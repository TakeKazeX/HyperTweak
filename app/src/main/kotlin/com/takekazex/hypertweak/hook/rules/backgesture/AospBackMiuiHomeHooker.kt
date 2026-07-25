package com.takekazex.hypertweak.hook.rules.backgesture

import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.backgesture.AospBackGestureRuntime
import com.takekazex.hypertweak.hook.rules.backgesture.AospBackGestureRuntimeProvider

object AospBackMiuiHomeHooker : StaticHooker() {
    override fun onHook() {
        AospBackGestureRuntimeProvider.runtime.installMiuiHomeHooks(
            classLoader,
            AospBackGestureRuntime.HookRegistrar { method, id, hooker ->
                registerRuntimeHook(method, id, hooker)
            }
        )
    }
}
