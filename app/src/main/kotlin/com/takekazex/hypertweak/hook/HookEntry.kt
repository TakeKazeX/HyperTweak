package com.takekazex.hypertweak.hook

import android.content.pm.ApplicationInfo
import android.util.Log
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.base.ModuleContext
import com.takekazex.hypertweak.hook.rules.systemui.AODHooker
import com.takekazex.hypertweak.hook.rules.systemui.HideFingerprintIcon
import com.takekazex.hypertweak.hook.rules.systemui.HideBottomBarHooker
import com.takekazex.hypertweak.hook.rules.module.ModuleStatusHooker
import com.takekazex.hypertweak.hook.rules.module.SettingsHooker
import com.takekazex.hypertweak.hook.rules.system.SystemConfigHooker
import com.takekazex.hypertweak.hook.rules.system.PasskeyHooker
import com.takekazex.hypertweak.hook.rules.system.SpatialAudioBlockerHooker
import com.takekazex.hypertweak.hook.rules.systemui.SystemUIPluginHooker
import com.takekazex.hypertweak.hook.rules.module.RestartBroadcastHooker
import com.takekazex.hypertweak.hook.rules.settings.BluetoothPluginHooker
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
        initPreferences()
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        EzXposed.initOnSystemServerStarting(param)
        dispatchSystemServerHookers(param.classLoader)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (!injectedPackages.add(param.packageName)) return
        EzXposed.initOnPackageLoaded(param)
        EzReflect.init(param.defaultClassLoader)

        dispatchPackageHookers(
            packageName = param.packageName,
            classLoader = param.defaultClassLoader,
            appInfo = param.applicationInfo,
            isFirstPackage = param.isFirstPackage
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // Establishes the target snapshot required for hot reload state restore.
        EzXposed.initOnPackageReady(param)
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        // Runs in OLD code: save the target snapshot so the new generation can restore it.
        return EzXposed.handleHotReloading(param)
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        // Runs in NEW code: unhooks old handles, restores classLoader/package, then re-hooks.
        EzXposed.handleHotReloaded(this, param)
        initPreferences()

        val targetClassLoader = runCatching { EzReflect.classLoader }.getOrNull() ?: return
        val packageName = EzXposed.packageName
        injectedPackages.clear()

        if (EzXposed.isSystemServer) {
            dispatchSystemServerHookers(targetClassLoader)
        } else if (packageName.isNotEmpty()) {
            injectedPackages.add(packageName)
            val appInfo = runCatching { EzXposed.appContextOrNull?.applicationInfo }.getOrNull()
            dispatchPackageHookers(
                packageName = packageName,
                classLoader = targetClassLoader,
                appInfo = appInfo,
                isFirstPackage = false
            )
        }
    }

    private fun initPreferences() {
        try {
            val remotePrefs = getRemotePreferences(Preferences.NAME)
            Preferences.init(remotePrefs)
            Log.d("HyperTweak", "HookEntry: processName=$processName, loaded remotePrefs keys=${remotePrefs.all.keys}")
        } catch (t: Throwable) {
            Log.e("HyperTweak", "Failed to init Preferences in HookEntry", t)
        }
    }

    private fun dispatchSystemServerHookers(classLoader: ClassLoader) {
        val ctx = ModuleContext(
            processName = processName,
            packageName = "system",
            isSystemServer = true
        )
        attachHooker(SystemConfigHooker, classLoader, ctx)
        attachHooker(PasskeyHooker, classLoader, ctx)
    }

    private fun dispatchPackageHookers(
        packageName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo?,
        isFirstPackage: Boolean
    ) {
        val ctx = ModuleContext(
            processName = processName,
            packageName = packageName,
            isSystemServer = isSystemServer,
            isFirstPackage = isFirstPackage,
            isPackageReady = false,
            appInfo = appInfo
        )

        when (packageName) {
            "com.android.systemui" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx)
                attachHooker(AODHooker, classLoader, ctx)
                attachHooker(HideFingerprintIcon, classLoader, ctx)
                attachHooker(SystemUIPluginHooker, classLoader, ctx)
                attachHooker(HideBottomBarHooker, classLoader, ctx)
            }
            "com.miui.aod" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx)
                attachHooker(AODHooker, classLoader, ctx)
            }
            "com.android.settings" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx)
                attachHooker(SettingsHooker, classLoader, ctx)
                attachHooker(AODHooker, classLoader, ctx)
                attachHooker(PasskeyHooker, classLoader, ctx)
                attachHooker(BluetoothPluginHooker, classLoader, ctx)
            }
            "com.miui.securitycenter" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx)
                attachHooker(PasskeyHooker, classLoader, ctx)
            }
            "com.xiaomi.scanner" -> {
                attachHooker(RestartBroadcastHooker, classLoader, ctx)
                attachHooker(PasskeyHooker, classLoader, ctx)
            }
            "com.milink.service" -> {
                attachHooker(SpatialAudioBlockerHooker, classLoader, ctx)
            }
            "com.takekazex.hypertweak" -> {
                attachHooker(ModuleStatusHooker, classLoader, ctx)
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
