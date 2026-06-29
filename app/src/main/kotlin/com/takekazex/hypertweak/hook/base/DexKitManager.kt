package com.takekazex.hypertweak.hook.base

import android.content.Context
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object DexKitManager {
    private const val PREFS_NAME = "hypertweak_dexkit_cache"
    private const val KEY_LAST_MODIFIED = "apk_last_modified"
    private val bridgeLock = ReentrantLock()
    private val bridgeDrained = bridgeLock.newCondition()

    @Volatile
    private var isLoaded = false

    @Volatile
    private var activeBridgeUsers = 0

    @Volatile
    private var hotReloadPreparing = false

    fun loadLibrary(): Boolean {
        if (hotReloadPreparing) {
            DebugLog.w("DexKit", "skip loading native library during hot reload preparation")
            return false
        }
        bridgeLock.withLock {
            if (hotReloadPreparing) {
                DebugLog.w("DexKit", "skip loading native library during hot reload preparation")
                return false
            }
            if (isLoaded) return true
            try {
                System.loadLibrary("dexkit")
                isLoaded = true
                DebugLog.d("DexKit", "native library loaded")
            } catch (t: Throwable) {
                DebugLog.e("DexKit", "failed to load native library", t)
            }
            return isLoaded
        }
    }

    fun prepareForHotReload(timeoutMs: Long = 1500L): Boolean {
        bridgeLock.withLock {
            hotReloadPreparing = true
            if (activeBridgeUsers == 0) {
                DebugLog.d("DexKit", "hot reload preparation complete; no active bridge users")
                return true
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            while (activeBridgeUsers > 0) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                runCatching { bridgeDrained.awaitNanos(remaining * 1_000_000L) }
            }

            val ready = activeBridgeUsers == 0
            if (ready) {
                DebugLog.d("DexKit", "hot reload preparation complete; active bridge users drained")
            } else {
                hotReloadPreparing = false
                DebugLog.w("DexKit", "hot reload preparation timed out; activeBridgeUsers=$activeBridgeUsers")
            }
            return ready
        }
    }

    fun cancelHotReloadPreparation() {
        bridgeLock.withLock {
            hotReloadPreparing = false
            bridgeDrained.signalAll()
        }
    }

    private fun enterBridgeSession(): Boolean {
        bridgeLock.withLock {
            if (hotReloadPreparing) return false
            activeBridgeUsers++
            return true
        }
    }

    private fun exitBridgeSession() {
        bridgeLock.withLock {
            activeBridgeUsers = (activeBridgeUsers - 1).coerceAtLeast(0)
            bridgeDrained.signalAll()
        }
    }

    fun <T> withBridge(apkPath: String, block: (DexKitBridge) -> T): T? {
        if (!enterBridgeSession()) {
            DebugLog.w("DexKit", "skip bridge creation during hot reload preparation")
            return null
        }
        try {
            if (!loadLibrary()) {
                DebugLog.e("DexKit", "not loaded; cannot create bridge")
                return null
            }
            return runCatching {
                DexKitBridge.create(apkPath).use(block)
            }.onFailure { t ->
                DebugLog.e("DexKit", "failed to run bridge for APK $apkPath", t)
            }.getOrNull()
        } finally {
            exitBridgeSession()
        }
    }

    /**
     * Resolves the required classes either from cache or by performing a DexKit scan.
     * @param cacheDir Cache directory of the target package (used to store properties cache)
     * @param apkPath Absolute path to the APK being scanned
     * @param classLoader ClassLoader of the target package
     * @param queries A map of cacheKey to query function (DexKitBridge) -> String (ClassName)
     * @return Map of cacheKey to Resolved ClassName
     */
    @Synchronized
    fun resolveClasses(
        cacheDir: File?,
        apkPath: String,
        classLoader: ClassLoader,
        queries: Map<String, (DexKitBridge) -> String?>,
        logMissingQueries: Boolean = true
    ): Map<String, Class<*>> {
        if (!loadLibrary()) {
            DebugLog.e("DexKit", "not loaded; falling back to default names")
            return emptyMap()
        }

        if (cacheDir == null) {
            DebugLog.w("DexKit", "cacheDir is null; resolving without cache")
        }

        val cacheFile = if (cacheDir != null) File(cacheDir, "hypertweak_dexkit_cache.properties") else null
        val properties = java.util.Properties()
        
        if (cacheFile != null && cacheFile.exists()) {
            runCatching {
                cacheFile.inputStream().use { properties.load(it) }
            }.onFailure { t ->
                DebugLog.e("DexKit", "failed to load properties cache", t)
            }
        }

        val apkFile = File(apkPath)
        val currentLastModified = apkFile.lastModified()
        val cachedLastModified = properties.getProperty(KEY_LAST_MODIFIED)?.toLongOrNull() ?: 0L

        val isCacheValid = currentLastModified > 0 && currentLastModified == cachedLastModified
        val resolvedMap = mutableMapOf<String, Class<*>>()
        val missingQueries = mutableMapOf<String, (DexKitBridge) -> String?>()

        // 1. Try reading from cache first
        if (isCacheValid) {
            DebugLog.d("DexKit", "cache is valid; reading class names")
            for ((key, _) in queries) {
                val cachedName = properties.getProperty(key)
                if (cachedName != null) {
                    runCatching {
                        val clazz = classLoader.loadClass(cachedName)
                        resolvedMap[key] = clazz
                    }.onFailure {
                        DebugLog.w("DexKit", "failed to load cached class $cachedName for key $key")
                        missingQueries[key] = queries[key]!!
                    }
                } else {
                    missingQueries[key] = queries[key]!!
                }
            }
        } else {
            DebugLog.d("DexKit", "cache invalid or target APK updated; scanning")
            missingQueries.putAll(queries)
        }

        // 2. Perform DexKit scan for missing keys
        if (missingQueries.isNotEmpty()) {
            DebugLog.d("DexKit", "performing scan for ${missingQueries.size} classes")
            val startTime = System.currentTimeMillis()
            withBridge(apkPath) { bridge ->
                var cacheUpdated = false
                for ((key, queryFunc) in missingQueries) {
                    val className = queryFunc(bridge)
                    if (className != null) {
                        runCatching {
                            val clazz = classLoader.loadClass(className)
                            resolvedMap[key] = clazz
                            properties.setProperty(key, className)
                            cacheUpdated = true
                            DebugLog.d("DexKit", "resolved $key -> $className")
                        }.onFailure { t ->
                            DebugLog.e("DexKit", "resolved $className for key $key but class load failed", t)
                        }
                    } else {
                        if (logMissingQueries) {
                            DebugLog.e("DexKit", "query returned null for key $key")
                        } else {
                            DebugLog.d("DexKit", "optional query returned null for key $key")
                        }
                    }
                }
                if (cacheUpdated && cacheFile != null && cacheDir != null) {
                    properties.setProperty(KEY_LAST_MODIFIED, currentLastModified.toString())
                    runCatching {
                        if (!cacheDir.exists()) cacheDir.mkdirs()
                        cacheFile.outputStream().use { properties.store(it, "HyperTweak DexKit Cache") }
                    }.onFailure { t ->
                        DebugLog.e("DexKit", "failed to write properties cache", t)
                    }
                }
            }
            DebugLog.d("DexKit", "scan completed in ${System.currentTimeMillis() - startTime} ms")
        }

        return resolvedMap
    }
}
