package com.takekazex.hypertweak.hook.rules.module

import com.takekazex.hypertweak.hook.base.HostSelect
import com.takekazex.hypertweak.util.logging.DebugLog
import com.takekazex.hypertweak.hook.base.StaticHooker

object ModuleStatusHooker : StaticHooker() {
    override fun onHook() {
        val clzMainActivity = "com.takekazex.hypertweak.MainActivity".toClassOrNull() ?: run { DebugLog.hookSkipped("ModuleStatusHooker", "com.takekazex.hypertweak.MainActivity", "not found on host"); return }
        HostSelect.method(clzMainActivity) {
            name("isModuleActive")
        }?.hook {
            before { param ->
                param.result = true
            }
        }
    }
}
