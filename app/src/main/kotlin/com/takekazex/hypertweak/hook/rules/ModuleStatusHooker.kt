package com.takekazex.hypertweak.hook.rules

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.takekazex.hypertweak.hook.base.StaticHooker

object ModuleStatusHooker : StaticHooker() {
    override fun onHook() {
        val clzMainActivity = "com.takekazex.hypertweak.MainActivity".toClassOrNull() ?: return
        val metIsModuleActive = clzMainActivity.resolve().firstMethodOrNull {
            name = "isModuleActive"
        } ?: return

        metIsModuleActive.hook {
            result(true)
        }
    }
}
