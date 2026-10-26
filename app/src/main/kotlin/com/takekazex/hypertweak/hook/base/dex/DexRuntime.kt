package com.takekazex.hypertweak.hook.base.dex

import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.util.logging.DebugLog
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import android.os.Process
import android.content.pm.ApplicationInfo
import java.util.concurrent.ConcurrentHashMap
import com.takekazex.hypertweak.hook.base.DexKitCacheIdentity
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A live backend session. [dexKitBridge] is non-null only while the process is inside a DexKit
 * session; `MethodData` reads must stay inside the callback, exactly like `DexKitManager.withBridge`.
 */
internal class DexSession internal constructor(
    val backend: DexBackendId,
    val apkPath: String,
    private val dexKitBridge: DexKitBridge?,
) {
    fun <T> dexKit(block: (DexKitBridge) -> T): T? = dexKitBridge?.let(block)
}

/**
 * Shared lifecycle for DEX-backed resolution.
 *
 * Features describe *what* to resolve (a [DexFeature] plus encode/decode) and this runtime owns
 * *when* and *where*: it reads the build-keyed cache first, reuses one backend session per package
 * dispatch, writes the cache only when a scan succeeded, and keeps the backend identity in the
 * file so an external engine can produce the same format.
 *
 * Full byte identities are memoized per installed base/split metadata. Features whose targets
 * are not needed during bindApplication prepare them on the managed background worker.
 */
internal object DexRuntime {
    private const val TAG = "DexRuntime"
    private const val IDENTITY_MAX_AGE_NANOS = 60_000_000_000L
    private val resolutions = DexResolution()
    private val identities = ConcurrentHashMap<String, DexKitCacheIdentity>()
    private val identityTimes = ConcurrentHashMap<String, Long>()
    @Volatile private var executingInfo: ApplicationInfo? = null
    private val sourceInfos = ConcurrentHashMap<String, ApplicationInfo>()

    /** PackageLoaded precedes Application creation; retain its split contract for legacy callers. */
    fun registerSource(info: ApplicationInfo?) {
        info?.sourceDir?.takeIf(String::isNotBlank)?.let {
            executingInfo = info
            sourceInfos[it] = info
            invalidateSource(it)
        }
    }
    /** Package dispatch/update is an explicit identity epoch, including equal-stat replacements. */
    fun invalidateSource(path: String) {
        synchronized(identities) { identities.remove(path); identityTimes.remove(path) }
    }
    private val scopes = DexScopes(DexKitManager::beginSharedSession)
    val isResolving: Boolean get() = DexKitManager.hasActiveUsers
    /**
     * Background resolution worker.
     *
     * Deliberately single-threaded. Each task may open a DexKit bridge over the host APK, and two
     * concurrent bridges on an 81 MB APK cost more memory than the queueing they save. A slow
     * feature is therefore fixed by caching it, not by adding a second thread: `PowerRanking` held
     * this worker for 7.8 s until its descriptors were cached, which is what had been delaying the
     * Dock install behind it.
     */
    private val worker = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(32), { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "HT-DexPrepare").apply { isDaemon = true }
    }).apply { allowCoreThreadTimeOut(true) }

    private val batches = DexWorkBatch(
        execute = { worker.execute(it) },
        withScope = { path, action -> withScope(path, action) },
        failure = { DebugLog.w(TAG, "background DEX batch failed", it) },
    )

    fun beginSession(apkPath: String): AutoCloseable = scopes.begin(apkPath)

    /** The executing package owns caches, including queries against plugin APKs. */
    fun cacheDirectory(info: ApplicationInfo?): File? =
        (info?.deviceProtectedDataDir?.takeIf(String::isNotBlank)
            ?: info?.dataDir?.takeIf(String::isNotBlank))?.let { File(it, "cache") }

    /** Complete base/split byte identity, memoized per installed metadata in this process. */
    fun identityFor(info: ApplicationInfo?, apkPath: String): DexKitCacheIdentity? {
        val apk = File(apkPath)
        val key = apk.absolutePath
        val modified = apk.lastModified()
        val size = apk.length()
        // Callers cannot be trusted to thread ApplicationInfo through: only a handful of the
        // resolve() sites have it in scope, and a missing one would silently drop split coverage
        // and reintroduce the stale-descriptor bug. The executing process always has an
        // ApplicationInfo of its own, so it is used as the fallback rather than the exception.
        val sourceInfo = info?.takeIf { it.sourceDir == apkPath } ?: sourceInfos[apkPath]
            ?: selfApplicationInfo()?.takeIf { it.sourceDir == apkPath }
        val splits = sourceInfo?.splitSourceDirs?.filterNotNull().orEmpty()
        // The memo is keyed by base APK path, but the identity also covers the splits. A split
        // that is installed or replaced in place leaves base.apk's size and mtime untouched, so
        // returning the memoized identity would keep serving the old split stamps - exactly the
        // stale-descriptor case the split coverage exists to prevent. Split metadata is therefore
        // re-read on every call; it is a handful of stat calls, not a file read.
        val stamps = splits.distinct().sorted().map { path ->
            val split = File(path)
            DexKitCacheIdentity.SplitStamp(path, split.length(), split.lastModified())
        }
        identities[key]?.let { cached ->
            if (cached.modified == modified && cached.size == size && cached.splits == stamps &&
                System.nanoTime() - (identityTimes[key] ?: 0L) < IDENTITY_MAX_AGE_NANOS) return cached
        }
        return synchronized(identities) {
            identities[key]?.takeIf { it.modified == modified && it.size == size && it.splits == stamps &&
                System.nanoTime() - (identityTimes[key] ?: 0L) < IDENTITY_MAX_AGE_NANOS }
                ?: DexKitCacheIdentity.capture(apk, splits, fingerprintFor(apkPath))
                    ?.let(DexKitCacheIdentity::withFullDigest)
                    ?.takeIf { !it.fullDigest.isNullOrBlank() }
                    ?.also { identities[key] = it; identityTimes[key] = System.nanoTime() }
        }
    }

    /** The executing process's own ApplicationInfo; available after the application is attached. */
    private fun selfApplicationInfo(): ApplicationInfo? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val application = activityThread.getDeclaredMethod("currentApplication").invoke(null)
        (application as? android.content.Context)?.applicationInfo
    }.getOrNull()

    /**
     * System-partition packages keep their path and size across an OTA, so the build fingerprint
     * is the only cheap metadata that distinguishes builds. Installed apps get a new random
     * `/data/app` directory instead and need no second factor.
     */
    private fun fingerprintFor(apkPath: String): String? {
        if (!apkPath.startsWith("/system") && !apkPath.startsWith("/product") &&
            !apkPath.startsWith("/system_ext") && !apkPath.startsWith("/vendor")
        ) return null
        return runCatching { android.os.Build.FINGERPRINT }.getOrNull()?.takeIf(String::isNotBlank)
    }

    fun <T> withScope(apkPath: String?, block: () -> T): T {
        val scope = apkPath?.let(::beginSession)
        return try { block() } finally { scope?.close() }
    }

    /** Explicit containers: base-only is intentional unless a caller supplies a split set.
     * Identity still covers declared splits. Duplicate classes fail closed; no loader-order guess.
     * Cache ownership always belongs to the executing host, independent of the source container.
     */
    fun operands(
        apkPath: String,
        cacheOwner: ApplicationInfo? = null,
        containers: List<String> = listOf(apkPath),
    ): DexOperandIndex {
        if (cacheOwner != null && executingInfo == null) executingInfo = cacheOwner
        require(containers.isNotEmpty() && containers.distinct().size == containers.size)
        val identity = requireNotNull(identityFor(null, apkPath)) { "Cannot verify operand source" }
        val sourceKey = containers.joinToString("|") { path ->
            val stamp = requireNotNull(identityFor(null, path)) { "Cannot verify operand container: $path" }
            path + ":" + stamp.resolutionKey()
        }
        return scopes.artifact(apkPath, DexOperandIndex::class.java,
            "operand-v2:" + identity.resolutionKey() + ":" + sourceKey) {
            // Resolve cache owner at query time; an index never retains the first caller's info.
            lateinit var index: DexOperandIndex
            index = DexOperandIndex(containers.map(::File)) { value ->
                val key = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((sourceKey + "\u0000" + value).toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val descriptors = containers.flatMap { container ->
                    resolve(
                        feature = object : DexFeature {
                            override val id = "operand.string.$key"
                            override val schema = 1
                        }, apkPath = container,
                        cacheDir = cacheDirectory(selfApplicationInfo() ?: executingInfo), cacheEmpty = true,
                        compute = { session ->
                            val found = requireNotNull(session.dexKit { bridge ->
                                bridge.findMethod { matcher {
                                    addUsingString(value, org.luckypray.dexkit.query.enums.StringMatchType.Equals)
                                } }.map { it.descriptor }.distinct().sorted()
                            }) { "Operand string backend unavailable" }
                            found.mapIndexed { position, descriptor -> position.toString() to descriptor }.toMap()
                        },
                        decode = { values, _ -> values.toSortedMap().values.toList().takeIf { list -> list.all { index.matchesString(it, value) } } },
                    ) ?: error("Operand string lookup failed: $container")
                }
                descriptors.distinct()
            }
            index
        }
    }

    fun preparation(
        apkPath: String? = null,
        publish: (() -> Unit) -> Unit,
        failure: (Throwable) -> Unit,
        afterStartup: (() -> Unit) -> Unit = { it() },
    ): DexPreparation = DexPreparation(
        background = { batches.submit(apkPath, it) }, publish = publish, failure = failure, afterStartup = afterStartup,
    )

    /** Queues [block] on the single background preparation worker. */
    fun background(block: () -> Unit): Boolean = runCatching { worker.execute(block) }.isSuccess

    /** Compatibility queries share the same session and timing boundary; no bridge escapes. */
    fun <T> query(apkPath: String, feature: String, block: (DexKitBridge) -> T): T? {
        val started = System.nanoTime()
        return try { withSession(apkPath) { it.dexKit(block) } } finally {
            val duration = elapsed(started)
            if (duration >= 250L) DebugLog.i(TAG,
                "query=" + feature + " durationMs=" + duration + " thread=" + Thread.currentThread().name)
        }
    }

    /** One authoritative cache path for legacy class queries as well as typed feature codecs. */
    fun resolveClasses(
        cacheDir: File?,
        apkPath: String,
        classLoader: ClassLoader,
        queries: Map<String, (DexKitBridge) -> String?>,
        logMissingQueries: Boolean = true,
        validators: Map<String, (Class<*>) -> Boolean> = emptyMap(),
        appInfo: ApplicationInfo? = null,
    ): Map<String, Class<*>> {
        val scope = beginSession(apkPath)
        return try {
            buildMap {
                queries.forEach { (key, query) ->
                    val feature = object : DexFeature {
                        override val id = "class." + key
                        override val schema = 1
                    }
                    val target = resolve(feature, apkPath, cacheDir, appInfo = appInfo,
                        compute = { session ->
                            session.dexKit { bridge -> query(bridge) }?.let { mapOf("class" to it) }.orEmpty()
                        },
                        decode = { values, _ ->
                            values["class"]?.let { name ->
                                Class.forName(name, false, classLoader).takeIf { validators[key]?.invoke(it) != false }
                            }
                        },
                    )
                    if (target != null) put(key, target)
                    else if (logMissingQueries) DebugLog.w(TAG, "class query=" + key + " unavailable; dependent hook skipped")
                }
            }
        } finally { scope.close() }
    }

    /** Persist named optional method targets; reject the entire entry if a stored member is invalid. */
    fun resolveMethodMap(
        id: String,
        apkPath: String,
        cacheDir: File?,
        classLoader: ClassLoader,
        appInfo: ApplicationInfo? = null,
        validate: (String, java.lang.reflect.Method) -> Boolean,
        compute: (DexKitBridge) -> Map<String, java.lang.reflect.Method>,
    ): Map<String, java.lang.reflect.Method>? = resolve(
        feature = object : DexFeature { override val id = id; override val schema = 1 },
        apkPath = apkPath, cacheDir = cacheDir, appInfo = appInfo, cacheEmpty = true,
        compute = { session -> requireNotNull(session.dexKit { bridge ->
            compute(bridge).mapValues { MethodDescriptorCodec.descriptor(it.value) }
        }) { "Method-map backend unavailable" } },
        decode = { values, _ ->
            val methods = values.mapValues { (key, descriptor) ->
                MethodDescriptorCodec.method(descriptor, classLoader)?.takeIf { validate(key, it) }
            }
            if (methods.values.any { it == null }) null
            else methods.mapValues { requireNotNull(it.value) }
        },
    )

    /**
     * Resolves [feature] for [apkPath].
     *
     * [cacheOnly] answers from the cache without opening a backend (used by auxiliary processes
     * that must not miss ActivityManager's provider deadline). [cacheEmpty] records a legitimate
     * "no target on this build" result; otherwise an empty scan is not persisted.
     */
    fun <T : Any> resolve(
        feature: DexFeature,
        apkPath: String,
        cacheDir: File?,
        cacheOnly: Boolean = false,
        cacheEmpty: Boolean = false,
        appInfo: ApplicationInfo? = null,
        compute: (DexSession) -> Map<String, String>,
        decode: (Map<String, String>, Boolean) -> T?,
    ): T? {
        val apk = File(apkPath)
        if (!apk.isFile) return null
        val started = System.nanoTime()
        val identity = identityFor(appInfo, apkPath) ?: return null
        val store = DexCacheStore(feature, cacheDir)
        var backend = DexBackendId.DEXKIT
        return resolutions.resolve(
            key = feature.id + ":" + feature.schema + ":" + apkPath + ":" + identity.resolutionKey(),
            cacheOnly = cacheOnly,
            read = { store.read(apk, identity) },
            scan = {
                runCatching {
                    withSession(apkPath) { session -> backend = session.backend; compute(session) }
                }.onFailure { DebugLog.w(TAG, "feature=" + feature.id + " resolution failed", it) }.getOrNull()
            },
            decode = { values, cached ->
                decode(values, cached)?.also {
                    DebugLog.i(TAG, "feature=" + feature.id + " " + (if (cached) "cache hit" else "scanned") +
                        " in " + elapsed(started) + "ms values=" + values.size +
                        " thread=" + Thread.currentThread().name)
                }
            },
            write = { values ->
                if (values.isNotEmpty() || cacheEmpty) store.write(apk, values, identity, backend)
            },
            invalid = { DebugLog.w(TAG, "feature=" + feature.id + " descriptors rejected", it) },
        )
    }

    /**
     * Runs [block] against the package's active session, reusing the dispatch-wide bridge opened by
     * [DexKitManager.beginSharedSession] when present. The bridge is closed when the outermost
     * session ends; [block] must materialize every reflective target it needs before returning.
     */
    fun <T> withSession(apkPath: String, block: (DexSession) -> T): T? {
        val scope = beginSession(apkPath)
        return try {
            DexKitManager.withBridge(apkPath) { bridge ->
                block(DexSession(DexBackendId.DEXKIT, apkPath, bridge))
            }
        } finally {
            scope.close()
        }
    }

    private fun elapsed(started: Long): Long = (System.nanoTime() - started) / 1_000_000L
}