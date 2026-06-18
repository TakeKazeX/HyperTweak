package com.takekazex.hypertweak.hook

import android.content.pm.ApplicationInfo
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
import com.takekazex.hypertweak.util.DebugLog
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
        DebugLog.bindXposed(this)
        initPreferences()
        DebugLog.d("HookEntry", "module loaded process=$processName isSystemServer=$isSystemServer")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        EzXposed.initOnSystemServerStarting(param)
        DebugLog.d("HookEntry", "system_server starting")
        dispatchSystemServerHookers(param.classLoader)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (!injectedPackages.add(param.packageName)) return
        EzXposed.initOnPackageLoaded(param)
        EzReflect.init(param.defaultClassLoader)
        DebugLog.d(
            "HookEntry",
            "package loaded package=${param.packageName} process=$processName first=${param.isFirstPackage}"
        )

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

        val appContext = runCatching { EzXposed.appContextOrNull }.getOrNull()
        if (appContext != null) {
            Preferences.initLocalCache(appContext)
            RestartBroadcastHooker.register(appContext)
            DebugLog.d("HookEntry", "package ready package=${param.packageName} context=${appContext.packageName}")
        } else {
            DebugLog.w("HookEntry", "package ready package=${param.packageName} without app context")
        }

        if (param.packageName == "com.android.systemui") {
            HideBottomBarHooker.onPackageReady(appContext, param.classLoader)
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        // Runs in OLD code: save the target snapshot so the new generation can restore it.
        DebugLog.d("HookEntry", "hot reloading old generation process=$processName")
        return EzXposed.handleHotReloading(param)
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        // Runs in NEW code: unhooks old handles, restores classLoader/package, then re-hooks.
        EzXposed.handleHotReloaded(this, param)
        initPreferences()
        DebugLog.d("HookEntry", "hot reloaded new generation package=${EzXposed.packageName}")

        val targetClassLoader = runCatching { EzReflect.classLoader }.getOrNull() ?: run {
            DebugLog.w("HookEntry", "hot reloaded but target classLoader is unavailable")
            return
        }
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
            DebugLog.d("HookEntry", "processName=$processName loaded remotePrefs keys=${remotePrefs.all.keys}")
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "failed to init Preferences", t)
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
            DebugLog.d("HookEntry", "attaching ${hooker::class.java.simpleName} package=${ctx.packageName}")
            hooker.module = this
            hooker.classLoader = targetClassLoader
            hooker.hookParam = ctx

            hooker.performInit()
            hooker.updateParentState(true)
        } catch (t: Throwable) {
            DebugLog.e("HookEntry", "failed to attach hooker: ${hooker::class.java.simpleName}", t)
        }
    }
}
