package com.ink.tweaks

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import com.ink.tweaks.hooker.AODHooker
import com.ink.tweaks.hooker.ModuleStatusHooker
import com.ink.tweaks.hooker.SystemConfigHooker

@InjectYukiHookWithXposed
class MainHook : IYukiHookXposedInit {

    override fun onInit() {
        YLog.Configs.apply {
            tag = "InkTweaks"
            isEnable = true
        }
    }

    override fun onHook() = encase {
        loadApp(name = BuildConfig.APPLICATION_ID) {
            loadHooker(ModuleStatusHooker)
        }
        loadApp(name = "com.android.systemui") {
            loadHooker(AODHooker)
        }
        loadApp(name = "android") {
            loadHooker(SystemConfigHooker)
        }
    }
}
