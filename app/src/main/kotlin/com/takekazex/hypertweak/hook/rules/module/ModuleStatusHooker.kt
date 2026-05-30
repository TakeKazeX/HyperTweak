package com.takekazex.hypertweak.hook.rules.module

import com.takekazex.hypertweak.hook.base.StaticHooker

object ModuleStatusHooker : StaticHooker() {
    override fun onHook() {
        val clzMainActivity = "com.takekazex.hypertweak.MainActivity".toClassOrNull() ?: return
        clzMainActivity.findMethodOrNull {
            name("isModuleActive")
        }?.hook {
            before { param ->
                param.result = true
            }
        }
    }
}
