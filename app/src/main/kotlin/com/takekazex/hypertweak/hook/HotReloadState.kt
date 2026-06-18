package com.takekazex.hypertweak.hook

import android.content.pm.ApplicationInfo

data class HotReloadPackageState(
    val packageName: String,
    val processName: String,
    val classLoader: ClassLoader,
    val appInfo: ApplicationInfo?,
    val isFirstPackage: Boolean,
    val isPackageReady: Boolean
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
                    pkg.isPackageReady
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
            HotReloadPackageState(
                packageName = pkg.getOrNull(0) as? String ?: return@mapNotNull null,
                processName = pkg.getOrNull(1) as? String ?: processName,
                classLoader = pkg.getOrNull(2) as? ClassLoader ?: return@mapNotNull null,
                appInfo = pkg.getOrNull(3) as? ApplicationInfo,
                isFirstPackage = pkg.getOrNull(4) as? Boolean ?: false,
                isPackageReady = pkg.getOrNull(5) as? Boolean ?: false
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
