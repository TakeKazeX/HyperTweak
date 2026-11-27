package com.takekazex.hypertweak.hook

import android.content.Context
import android.content.pm.ApplicationInfo
import com.takekazex.hypertweak.hook.base.BaseHooker
import com.takekazex.hypertweak.hook.runtime.reload.HotReloadPackageState
import com.takekazex.hypertweak.hook.runtime.reload.HotReloadPluginState
import com.takekazex.hypertweak.hook.runtime.settings.RuntimeSettingsGroups
import java.util.concurrent.ConcurrentHashMap

/**
 * One module generation's view of its host process: identity, installed root hookers and the
 * package snapshots that hot reload hands to the next generation. Owned by [HookEntry] and
 * shared with the settings, dispatch and hot-reload collaborators it composes.
 */
internal class HookProcessState {
    lateinit var processName: String
    var isSystemServer: Boolean = false
    var skipGoogleProcess = false
    var systemServerClassLoader: ClassLoader? = null
    @Volatile var generationRetired = false

    val injectedPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val rootHookers: MutableSet<BaseHooker> = ConcurrentHashMap.newKeySet()
    val packageStates = ConcurrentHashMap<String, HotReloadPackageState>()
    val pendingAppContextPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val runtimeGroups: Set<String> get() = RuntimeSettingsGroups.forProcess(processName, isSystemServer)
    val ownsRuntimeSettings: Boolean get() = runtimeGroups.isNotEmpty()

    fun recordPackageState(
        packageName: String,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo?,
        isFirstPackage: Boolean,
        isPackageReady: Boolean,
        appContext: Context?,
        pluginStates: List<HotReloadPluginState> = emptyList()
    ) {
        val old = packageStates[packageName]
        packageStates[packageName] = HotReloadPackageState(
            packageName = packageName,
            processName = processName,
            classLoader = classLoader,
            appInfo = appInfo ?: old?.appInfo,
            isFirstPackage = old?.isFirstPackage ?: isFirstPackage,
            isPackageReady = old?.isPackageReady == true || isPackageReady,
            appContext = appContext ?: old?.appContext,
            pluginStates = if (pluginStates.isNotEmpty()) pluginStates else old?.pluginStates.orEmpty()
        )
        if (packageStates[packageName]?.appContext != null) {
            pendingAppContextPackages.remove(packageName)
        }
    }
}
