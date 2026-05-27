package com.takekazex.hypertweak.hook

import android.util.Log
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.HookParam
import com.takekazex.hypertweak.hook.rules.AODHooker
import com.takekazex.hypertweak.hook.rules.HideFingerprintIcon
import com.takekazex.hypertweak.hook.rules.ModuleStatusHooker
import com.takekazex.hypertweak.hook.rules.SettingsHooker
import com.takekazex.hypertweak.hook.rules.SystemConfigHooker
import com.takekazex.hypertweak.hook.rules.SystemUIPluginHooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.ConcurrentHashMap

class HookEntry : XposedModule() {
    private val injectedPackages = ConcurrentHashMap.newKeySet<String>()
    private lateinit var processName: String
    private var isSystemServer: Boolean = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        isSystemServer = param.isSystemServer
        try {
            val remotePrefs = getRemotePreferences(Preferences.NAME)
            Preferences.init(remotePrefs)
        } catch (t: Throwable) {
            Log.e("HyperTweak", "Failed to init Preferences in HookEntry.onModuleLoaded", t)
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val hookParam = HookParam(
            processName = processName,
            packageName = "system",
            isSystemServer = isSystemServer
        )
        attachHooker(SystemConfigHooker, param.classLoader, hookParam)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (!injectedPackages.add(param.packageName)) return

        val hookParam = HookParam(
            processName = processName,
            packageName = param.packageName,
            isSystemServer = isSystemServer,
            isFirstPackage = param.isFirstPackage,
            isPackageReady = false,
            appInfo = param.applicationInfo
        )

        when (param.packageName) {
            "com.android.systemui" -> {
                attachHooker(AODHooker, param.defaultClassLoader, hookParam)
                attachHooker(HideFingerprintIcon, param.defaultClassLoader, hookParam)
                attachHooker(SystemUIPluginHooker, param.defaultClassLoader, hookParam)
            }
            "com.miui.aod" -> {
                attachHooker(AODHooker, param.defaultClassLoader, hookParam)
            }
            "com.android.settings" -> {
                attachHooker(SettingsHooker, param.defaultClassLoader, hookParam)
                attachHooker(AODHooker, param.defaultClassLoader, hookParam)
            }
            "com.takekazex.hypertweak" -> {
                attachHooker(ModuleStatusHooker, param.defaultClassLoader, hookParam)
            }
        }
    }

    private fun attachHooker(
        hooker: BaseHooker,
        targetClassLoader: ClassLoader,
        param: HookParam
    ) {
        try {
            hooker.module = this
            hooker.classLoader = targetClassLoader
            hooker.hookParam = param

            hooker.performInit()
            hooker.updateParentState(true)
        } catch (t: Throwable) {
            Log.e("HyperTweak", "Failed to attach hooker: ${hooker::class.java.simpleName}", t)
        }
    }
}
