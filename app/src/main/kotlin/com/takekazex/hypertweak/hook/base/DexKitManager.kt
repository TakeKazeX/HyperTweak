package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.util.concurrent.ConcurrentHashMap

object DexKitManager {
    private const val KEY_LAST_MODIFIED = "apk_last_modified"
    private const val KEY_FILE_SIZE = "apk_file_size"
    private const val KEY_SHA256 = "apk_sha256"
    private val bridgeLock = ReentrantLock()
    private val bridgeDrained = bridgeLock.newCondition()
    private val resolutionLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val fingerprintCache = ConcurrentHashMap<String, ApkFingerprint>()

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
    fun resolveClasses(
        cacheDir: File?,
        apkPath: String,
        classLoader: ClassLoader,
        queries: Map<String, (DexKitBridge) -> String?>,
        logMissingQueries: Boolean = true,
        /**
         * Semantic validators for cached/query results. Obfuscated camera class names can be
         * reused by a later APK, so loading a class successfully is not enough to reuse it.
         */
        validators: Map<String, (Class<*>) -> Boolean> = emptyMap(),
    ): Map<String, Class<*>> {
        val resolutionLock = resolutionLocks.computeIfAbsent(apkPath) { ReentrantLock() }
        resolutionLock.lock()
        try {
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
        val fingerprint = fingerprint(apkFile)
        val currentLastModified = fingerprint?.lastModified ?: 0L
        val currentFileSize = fingerprint?.size ?: 0L
        val currentSha256 = fingerprint?.sha256
        val cachedLastModified = properties.getProperty(KEY_LAST_MODIFIED)?.toLongOrNull() ?: 0L
        val cachedFileSize = properties.getProperty(KEY_FILE_SIZE)?.toLongOrNull() ?: 0L
        val cachedSha256 = properties.getProperty(KEY_SHA256)

        // mtime alone is not a content identity: package managers and backup/restore tools can
        // preserve it while replacing classes.dex. Old caches without a digest intentionally
        // fail closed and are rebuilt once.
        val isCacheValid = fingerprint != null &&
            currentLastModified == cachedLastModified &&
            currentFileSize == cachedFileSize &&
            currentSha256 != null && currentSha256 == cachedSha256
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
                        val validator = validators[key]
                        if (validator != null && !runCatching { validator(clazz) }.getOrDefault(false)) {
                            DebugLog.w("DexKit", "cached class $cachedName for key $key failed semantic validation")
                            properties.remove(key)
                            missingQueries[key] = queries[key]!!
                        } else {
                            resolvedMap[key] = clazz
                        }
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
            // Do not carry a previous build's class names into a newly fingerprinted cache. If a
            // scan cannot resolve an optional key, leaving the old name here would make it look
            // valid again on the next process start.
            queries.keys.forEach(properties::remove)
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
                            val validator = validators[key]
                            if (validator != null && !runCatching { validator(clazz) }.getOrDefault(false)) {
                                DebugLog.w("DexKit", "scanned class $className for key $key failed semantic validation")
                                properties.remove(key)
                            } else {
                                resolvedMap[key] = clazz
                                properties.setProperty(key, className)
                                cacheUpdated = true
                                DebugLog.d("DexKit", "resolved $key -> $className")
                            }
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
                    properties.setProperty(KEY_FILE_SIZE, currentFileSize.toString())
                    currentSha256?.let { properties.setProperty(KEY_SHA256, it) }
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
        } finally {
            resolutionLock.unlock()
        }
    }

    private data class ApkFingerprint(val lastModified: Long, val size: Long, val sha256: String)

    /**
     * Compute the complete APK digest once per on-disk version in this process. Camera hooks ask
     * for several independent classes during attach; the mtime/size guard avoids hashing the
     * same 200 MB APK repeatedly while still detecting a replaced file before the next process.
     */
    private fun fingerprint(file: File): ApkFingerprint? {
        if (!file.isFile) return null
        val lastModified = file.lastModified()
        val size = file.length()
        val cached = fingerprintCache[file.absolutePath]
        if (cached != null && cached.lastModified == lastModified && cached.size == size) return cached
        val digest = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) md.update(buffer, 0, count)
                }
            }
            md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.onFailure { t ->
            DebugLog.w("DexKit", "failed to hash APK ${file.absolutePath}; cache will not be reused", t)
        }.getOrNull() ?: return null
        return ApkFingerprint(lastModified, size, digest).also {
            fingerprintCache[file.absolutePath] = it
        }
    }
}
