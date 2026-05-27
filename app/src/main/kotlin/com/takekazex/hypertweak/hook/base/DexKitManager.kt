package com.takekazex.hypertweak.hook.base

import android.content.Context
import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import java.io.File

object DexKitManager {
    private const val PREFS_NAME = "hypertweak_dexkit_cache"
    private const val KEY_LAST_MODIFIED = "apk_last_modified"
    
    private var isLoaded = false
    
    @Synchronized
    fun loadLibrary() {
        if (isLoaded) return
        try {
            System.loadLibrary("dexkit")
            isLoaded = true
            Log.d("HyperTweak", "DexKit native library loaded successfully.")
        } catch (t: Throwable) {
            Log.e("HyperTweak", "Failed to load DexKit native library", t)
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
        queries: Map<String, (DexKitBridge) -> String?>
    ): Map<String, Class<*>> {
        loadLibrary()
        if (!isLoaded) {
            Log.e("HyperTweak", "DexKit not loaded. Falling back to default names.")
            return emptyMap()
        }

        if (cacheDir == null) {
            Log.e("HyperTweak", "DexKit cacheDir is null. Performing resolution without cache.")
        }

        val cacheFile = if (cacheDir != null) File(cacheDir, "hypertweak_dexkit_cache.properties") else null
        val properties = java.util.Properties()
        
        if (cacheFile != null && cacheFile.exists()) {
            runCatching {
                cacheFile.inputStream().use { properties.load(it) }
            }.onFailure { t ->
                Log.e("HyperTweak", "Failed to load properties cache", t)
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
            Log.d("HyperTweak", "DexKit cache is valid. Reading class names from cache.")
            for ((key, _) in queries) {
                val cachedName = properties.getProperty(key)
                if (cachedName != null) {
                    runCatching {
                        val clazz = classLoader.loadClass(cachedName)
                        resolvedMap[key] = clazz
                    }.onFailure {
                        Log.w("HyperTweak", "Failed to load cached class $cachedName for key $key")
                        missingQueries[key] = queries[key]!!
                    }
                } else {
                    missingQueries[key] = queries[key]!!
                }
            }
        } else {
            Log.d("HyperTweak", "DexKit cache is invalid or target APK updated. Will perform scan.")
            missingQueries.putAll(queries)
        }

        // 2. Perform DexKit scan for missing keys
        if (missingQueries.isNotEmpty()) {
            Log.d("HyperTweak", "Performing DexKit scan for ${missingQueries.size} classes...")
            val startTime = System.currentTimeMillis()
            runCatching {
                DexKitBridge.create(apkPath).use { bridge ->
                    var cacheUpdated = false
                    for ((key, queryFunc) in missingQueries) {
                        val className = queryFunc(bridge)
                        if (className != null) {
                            runCatching {
                                val clazz = classLoader.loadClass(className)
                                resolvedMap[key] = clazz
                                properties.setProperty(key, className)
                                cacheUpdated = true
                                Log.d("HyperTweak", "DexKit successfully resolved $key -> $className")
                            }.onFailure { t ->
                                Log.e("HyperTweak", "DexKit resolved $className for key $key but class load failed", t)
                            }
                        } else {
                            Log.e("HyperTweak", "DexKit query returned null for key $key")
                        }
                    }
                    if (cacheUpdated && cacheFile != null && cacheDir != null) {
                        properties.setProperty(KEY_LAST_MODIFIED, currentLastModified.toString())
                        runCatching {
                            if (!cacheDir.exists()) cacheDir.mkdirs()
                            cacheFile.outputStream().use { properties.store(it, "HyperTweak DexKit Cache") }
                        }.onFailure { t ->
                            Log.e("HyperTweak", "Failed to write properties cache", t)
                        }
                    }
                }
            }.onFailure { t ->
                Log.e("HyperTweak", "Failed to run DexKitBridge for APK $apkPath", t)
            }
            Log.d("HyperTweak", "DexKit scan completed in ${System.currentTimeMillis() - startTime} ms")
        }

        return resolvedMap
    }
}
