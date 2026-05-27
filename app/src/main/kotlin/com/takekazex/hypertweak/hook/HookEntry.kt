package com.takekazex.hypertweak.hook

import android.util.Log
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.ModuleContext
import com.takekazex.hypertweak.hook.rules.AODHooker
import com.takekazex.hypertweak.hook.rules.HideFingerprintIcon
import com.takekazex.hypertweak.hook.rules.ModuleStatusHooker
import com.takekazex.hypertweak.hook.rules.SettingsHooker
import com.takekazex.hypertweak.hook.rules.SystemConfigHooker
import com.takekazex.hypertweak.hook.rules.SystemUIPluginHooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.lingqiqi5211.ezhooktool.core.EzReflect
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import java.util.concurrent.ConcurrentHashMap

class HookEntry : XposedModule() {
    private val injectedPackages = ConcurrentHashMap.newKeySet<String>()
    private lateinit var processName: String
    private var isSystemServer: Boolean = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        isSystemServer = param.isSystemServer
        // Initialize EzXposed with the module interface
        EzXposed.initOnModuleLoaded(this, param)
        try {
            val remotePrefs = getRemotePreferences(Preferences.NAME)
            Preferences.init(remotePrefs)
        } catch (t: Throwable) {
            Log.e("HyperTweak", "Failed to init Preferences in HookEntry.onModuleLoaded", t)
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        EzXposed.initOnSystemServerStarting(param)
        val ctx = ModuleContext(
            processName = processName,
            packageName = "system",
            isSystemServer = isSystemServer
        )
        attachHooker(SystemConfigHooker, param.classLoader, ctx)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (!injectedPackages.add(param.packageName)) return
        EzXposed.initOnPackageLoaded(param)

        val ctx = ModuleContext(
            processName = processName,
            packageName = param.packageName,
            isSystemServer = isSystemServer,
            isFirstPackage = param.isFirstPackage,
            isPackageReady = false,
            appInfo = param.applicationInfo
        )

        EzReflect.init(param.defaultClassLoader)

        when (param.packageName) {
            "com.android.systemui" -> {
                attachHooker(AODHooker, param.defaultClassLoader, ctx)
                attachHooker(HideFingerprintIcon, param.defaultClassLoader, ctx)
                attachHooker(SystemUIPluginHooker, param.defaultClassLoader, ctx)
            }
            "com.miui.aod" -> {
                attachHooker(AODHooker, param.defaultClassLoader, ctx)
            }
            "com.android.settings" -> {
                attachHooker(SettingsHooker, param.defaultClassLoader, ctx)
                attachHooker(AODHooker, param.defaultClassLoader, ctx)
            }
            "com.takekazex.hypertweak" -> {
                attachHooker(ModuleStatusHooker, param.defaultClassLoader, ctx)
            }
        }
    }

    private fun attachHooker(
        hooker: BaseHooker,
        targetClassLoader: ClassLoader,
        ctx: ModuleContext
    ) {
        try {
            hooker.module = this
            hooker.classLoader = targetClassLoader
            hooker.hookParam = ctx

            hooker.performInit()
            hooker.updateParentState(true)
        } catch (t: Throwable) {
            Log.e("HyperTweak", "Failed to attach hooker: ${hooker::class.java.simpleName}", t)
        }
    }
}
