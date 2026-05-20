package com.ink.tweaks.hooker

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.*

object ModuleStatusHooker : YukiBaseHooker() {
    override fun onHook() {
        "com.ink.tweaks.MainActivity".toClass().method {
            name = "isModuleActive"
        }.hook {
            replaceAny {
                true
            }
        }
    }
}
