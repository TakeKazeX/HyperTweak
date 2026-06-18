package com.takekazex.hypertweak.hook

import android.content.pm.ApplicationInfo
import android.content.Context

data class HotReloadPackageState(
    val packageName: String,
    val processName: String,
    val classLoader: ClassLoader,
    val appInfo: ApplicationInfo?,
    val isFirstPackage: Boolean,
    val isPackageReady: Boolean,
    val appContext: Context?,
    val pluginStates: List<HotReloadPluginState> = emptyList()
)

data class HotReloadPluginState(
    val pluginInstance: Any,
    val componentPackage: String?,
    val componentClass: String?,
    val classLoader: ClassLoader,
    val appContext: Context?,
    val pluginApkPath: String
)

data class HotReloadTargetState(
    val processName: String,
    val isSystemServer: Boolean,
    val systemServerClassLoader: ClassLoader?,
    val packages: List<HotReloadPackageState>
)

object HotReloadState {
    private const val MAGIC = "HyperTweak.HotReloadState"
    private const val VERSION = 1

    fun save(
        processName: String,
        isSystemServer: Boolean,
        systemServerClassLoader: ClassLoader?,
        packages: Collection<HotReloadPackageState>
    ): Array<Any?> {
        return arrayOf(
            MAGIC,
            VERSION,
            processName,
            isSystemServer,
            systemServerClassLoader,
            packages.map { pkg ->
                arrayOf(
                    pkg.packageName,
                    pkg.processName,
                    pkg.classLoader,
                    pkg.appInfo,
                    pkg.isFirstPackage,
                    pkg.isPackageReady,
                    pkg.appContext,
                    pkg.pluginStates.map { plugin ->
                        arrayOf(
                            plugin.pluginInstance,
                            plugin.componentPackage,
                            plugin.componentClass,
                            plugin.classLoader,
                            plugin.appContext,
                            plugin.pluginApkPath
                        )
                    }.toTypedArray()
                )
            }.toTypedArray()
        )
    }

    fun restore(state: Any?): HotReloadTargetState? {
        val root = state as? Array<*> ?: return null
        if (root.size < 6 || root[0] != MAGIC || root[1] != VERSION) return null

        val processName = root[2] as? String ?: return null
        val isSystemServer = root[3] as? Boolean ?: return null
        val systemServerClassLoader = root[4] as? ClassLoader
        val packagesArray = root[5] as? Array<*> ?: emptyArray<Any?>()

        val packages = packagesArray.mapNotNull { item ->
            val pkg = item as? Array<*> ?: return@mapNotNull null
            val pluginArray = pkg.getOrNull(7) as? Array<*> ?: emptyArray<Any?>()
            val pluginStates = pluginArray.mapNotNull { pluginItem ->
                val plugin = pluginItem as? Array<*> ?: return@mapNotNull null
                HotReloadPluginState(
                    pluginInstance = plugin.getOrNull(0) ?: return@mapNotNull null,
                    componentPackage = plugin.getOrNull(1) as? String,
                    componentClass = plugin.getOrNull(2) as? String,
                    classLoader = plugin.getOrNull(3) as? ClassLoader ?: return@mapNotNull null,
                    appContext = plugin.getOrNull(4) as? Context,
                    pluginApkPath = plugin.getOrNull(5) as? String ?: ""
                )
            }
            HotReloadPackageState(
                packageName = pkg.getOrNull(0) as? String ?: return@mapNotNull null,
                processName = pkg.getOrNull(1) as? String ?: processName,
                classLoader = pkg.getOrNull(2) as? ClassLoader ?: return@mapNotNull null,
                appInfo = pkg.getOrNull(3) as? ApplicationInfo,
                isFirstPackage = pkg.getOrNull(4) as? Boolean ?: false,
                isPackageReady = pkg.getOrNull(5) as? Boolean ?: false,
                appContext = pkg.getOrNull(6) as? Context,
                pluginStates = pluginStates
            )
        }

        return HotReloadTargetState(
            processName = processName,
            isSystemServer = isSystemServer,
            systemServerClassLoader = systemServerClassLoader,
            packages = packages
        )
    }
}
