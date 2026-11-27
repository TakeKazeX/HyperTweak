package com.takekazex.hypertweak.hook.base.dex

import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.util.logging.DebugLog
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import android.os.Process
import android.content.pm.ApplicationInfo
import java.util.concurrent.ConcurrentHashMap
import com.takekazex.hypertweak.hook.base.DexKitCacheIdentity
import com.takekazex.hypertweak.util.ignoreFailure
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
    private val resolutions = DexResolution()
    private val identities = ConcurrentHashMap<String, DexKitCacheIdentity>()
    /** Kernel stat of base + splits captured with each memoized identity. */
    private val identityStats = ConcurrentHashMap<String, List<ApkFileStat?>>()
    /**
     * One capture at a time per APK path. A capture may hash the APK and wait for a sibling process
     * ([ApkDigestStore.digest]); holding a process-wide monitor for that made every other lookup
     * wait, including main-thread cache replays of unrelated APKs.
     */
    private val identityLocks = ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()
    /** Bumped by [invalidateSource]; a capture that started before the bump is not memoized. */
    private val identityEpochs = ConcurrentHashMap<String, Long>()
    @Volatile private var digestStore: ApkDigestStore? = null
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
        synchronized(identities) {
            identityEpochs.merge(path, 1L, Long::plus)
            identities.remove(path); identityStats.remove(path)
        }
    }
    private val scopes = DexScopes(DexKitManager::beginSharedSession)
    val isResolving: Boolean get() = DexKitManager.hasActiveUsers
    /** Hot-reload preflight: block new bridge sessions and wait for active users to drain. */
    fun prepareForRetirement(): Boolean = DexKitManager.prepareForHotReload()
    /** A rejected preflight reopens bridge sessions for the generation that stays live. */
    fun cancelRetirement() = DexKitManager.cancelHotReloadPreparation()
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
            ignoreFailure { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, WORKER_THREAD).apply { isDaemon = true }
    }).apply { allowCoreThreadTimeOut(true) }

    private const val WORKER_THREAD = "HT-DexPrepare"
    private val synchronousReason = ThreadLocal<String?>()
    private val replaying = ThreadLocal<Boolean?>()
    private val warnedInstallThreadScans = ConcurrentHashMap.newKeySet<String>()

    /**
     * Every resolution inside [block] answers from the cache only, so a resolver composed of several
     * lookups can be replayed on an install thread without one of them scanning. A miss anywhere
     * returns null from that lookup; the caller defers the whole resolver ([BaseHooker]'s
     * `prepareCachedDexTargets`).
     */
    fun <T> replayOnly(block: () -> T): T {
        val previous = replaying.get()
        replaying.set(true)
        try { return block() } finally { if (previous == null) replaying.remove() else replaying.set(previous) }
    }

    /** True inside [replayOnly]: a null result there is a cache miss, not a missing host contract. */
    val isReplayOnly: Boolean get() = replaying.get() == true

    /**
     * Declares that scans inside [block] must run synchronously on an install thread, with the
     * reason. Without it, [resolve] warns once per feature when a scan runs on the host main thread
     * or a binder thread: those are the install callbacks, which replay caches and defer scans.
     */
    fun <T> synchronousScan(reason: String, block: () -> T): T {
        val previous = synchronousReason.get()
        synchronousReason.set(reason)
        try { return block() } finally { synchronousReason.set(previous) }
    }

    private fun checkScanThread(feature: DexFeature) {
        val thread = Thread.currentThread()
        if (thread.name == WORKER_THREAD || synchronousReason.get() != null) return
        // Nested operand lookups run under their feature; that feature's line names the caller.
        if (feature.id.startsWith("operand.string.")) return
        val main = runCatching { android.os.Looper.getMainLooper()?.thread === thread }.getOrDefault(false)
        if (!main && !thread.name.startsWith("binder:")) return
        if (warnedInstallThreadScans.add(feature.id)) DebugLog.w(TAG, "feature=" + feature.id +
            " scanned on install thread=" + thread.name + "; replay the cache there and defer the scan " +
            "with prepareDexTargets/prepareDexContract, or declare DexRuntime.synchronousScan(reason)")
    }

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

    /**
     * Complete base/split byte identity, memoized per installed metadata in this process.
     *
     * [hashAllowed] false never reads APK bytes: an identity whose digest is neither memoized nor
     * persisted for the current stat is reported unavailable instead of hashed.
     */
    fun identityFor(info: ApplicationInfo?, apkPath: String, hashAllowed: Boolean = true): DexKitCacheIdentity? {
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
        // Revalidation is stat-only: inode and kernel ctime expose an equal-size/equal-mtime
        // replacement, so the full bytes are never re-read while the install is unchanged.
        val stats = (listOf(apkPath) + stamps.map { it.path }).map { ApkFileStat.of(File(it)) }
        fun reusable(cached: DexKitCacheIdentity) = cached.modified == modified && cached.size == size &&
            cached.splits == stamps && identityStats[key] == stats
        identities[key]?.takeIf(::reusable)?.let { return it }
        val lock = identityLocks.computeIfAbsent(key) { java.util.concurrent.locks.ReentrantLock() }
        // The host main thread never waits behind another thread's capture, which may be hashing or
        // waiting for a sibling process. On contention a replay reports the identity unavailable
        // (its caller defers to the worker), and a synchronous resolution captures it unlocked, at
        // worst hashing twice.
        val locked = if (isMainThread()) lock.tryLock() else { lock.lock(); true }
        if (!locked && !hashAllowed) return null
        try {
            identities[key]?.takeIf(::reusable)?.let { return it }
            val epoch = identityEpochs[key] ?: 0L
            val identity = DexKitCacheIdentity.capture(apk, splits, fingerprintFor(apkPath))
                ?.let { captured ->
                    val store = digestStore()
                    DexKitCacheIdentity.withFullDigest(captured,
                        if (hashAllowed) store::digest else { file -> checkNotNull(store.cached(file)) { "digest not persisted" } })
                }
                ?.takeIf { !it.fullDigest.isNullOrBlank() }
                ?: return null
            synchronized(identities) {
                // An invalidation during the capture means it may describe the previous install.
                if ((identityEpochs[key] ?: 0L) == epoch) { identities[key] = identity; identityStats[key] = stats }
            }
            return identity
        } finally {
            if (locked) lock.unlock()
        }
    }

    private fun isMainThread(): Boolean = runCatching {
        android.os.Looper.getMainLooper().let { it != null && it === android.os.Looper.myLooper() }
    }.getOrDefault(false)

    /** Persistent per-host digests; the executing package's cache directory owns the file. */
    private fun digestStore(): ApkDigestStore = digestStore ?: synchronized(this) {
        digestStore ?: ApkDigestStore(
            cacheDirectory(executingInfo ?: selfApplicationInfo())?.let { File(it, ApkDigestStore.FILE_NAME) },
            runCatching { android.os.Build.FINGERPRINT }.getOrNull().orEmpty(),
        ).also { store -> if (store.persistent) digestStore = store }
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
                if (Thread.currentThread().name != WORKER_THREAD) DebugLog.d(TAG) {
                    "operand string lookup off the worker value=" + value + " thread=" + Thread.currentThread().name
                }
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
        low: Boolean = false,
    ): DexPreparation = DexPreparation(
        background = { batches.submit(apkPath, low, it) }, publish = publish, failure = failure, afterStartup = afterStartup,
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
        cacheOnly: Boolean = false,
    ): Map<String, Class<*>> {
        val scope = beginSession(apkPath)
        return try {
            buildMap {
                queries.forEach { (key, query) ->
                    val feature = object : DexFeature {
                        override val id = "class." + key
                        override val schema = 1
                    }
                    val target = resolve(feature, apkPath, cacheDir, cacheOnly = cacheOnly, appInfo = appInfo,
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
        cacheOnly: Boolean = false,
        validate: (String, java.lang.reflect.Method) -> Boolean,
        compute: (DexKitBridge) -> Map<String, java.lang.reflect.Method>,
    ): Map<String, java.lang.reflect.Method>? = resolve(
        feature = object : DexFeature { override val id = id; override val schema = 1 },
        apkPath = apkPath, cacheDir = cacheDir, cacheOnly = cacheOnly, appInfo = appInfo, cacheEmpty = true,
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
        val replay = cacheOnly || replaying.get() == true
        return DexPhases.frame { phases ->
            resolveTimed(feature, apk, apkPath, cacheDir, replay, cacheEmpty, appInfo, compute, decode, phases)
        }
    }

    private fun <T : Any> resolveTimed(
        feature: DexFeature,
        apk: File,
        apkPath: String,
        cacheDir: File?,
        cacheOnly: Boolean,
        cacheEmpty: Boolean,
        appInfo: ApplicationInfo?,
        compute: (DexSession) -> Map<String, String>,
        decode: (Map<String, String>, Boolean) -> T?,
        phases: () -> String,
    ): T? {
        val started = System.nanoTime()
        // A cache-only replay on the main thread must not hash a cold 50-350 MB APK. A miss falls
        // through to the caller's background preparation, which hashes off the main thread.
        val mainThread = isMainThread()
        val onMain = cacheOnly && mainThread
        val identity = DexPhases.measure("identity") { identityFor(appInfo, apkPath, hashAllowed = !onMain) } ?: run {
            if (onMain) DebugLog.d(TAG) { "feature=" + feature.id + " identity cold on main thread; deferring" }
            return null
        }
        val store = DexCacheStore(feature, cacheDir)
        var backend = DexBackendId.DEXKIT
        return resolutions.resolve(
            key = feature.id + ":" + feature.schema + ":" + apkPath + ":" + identity.resolutionKey(),
            cacheOnly = cacheOnly,
            read = { DexPhases.measure("read") { store.read(apk, identity) } },
            scan = {
                checkScanThread(feature)
                runCatching {
                    DexPhases.measure("scan") {
                        withSession(apkPath) { session -> backend = session.backend; compute(session) }
                    }
                }.onFailure { DebugLog.w(TAG, "feature=" + feature.id + " resolution failed", it) }.getOrNull()
            },
            decode = { values, cached ->
                DexPhases.measure("decode") { decode(values, cached) }?.also {
                    DebugLog.i(TAG, "feature=" + feature.id + " " + (if (cached) "cache hit" else "scanned") +
                        " in " + elapsed(started) + "ms values=" + values.size +
                        " thread=" + Thread.currentThread().name + " phases=" + phases())
                }
            },
            write = { values ->
                if (values.isNotEmpty() || cacheEmpty) store.write(apk, values, identity, backend)
            },
            invalid = { DebugLog.w(TAG, "feature=" + feature.id + " descriptors rejected", it) },
            exclusive = { block -> HostFileLock.withLock(store.lockFile,
                onContended = { DexKitManager.releaseIdleBridge(apkPath) }, block = block) },
            wait = !mainThread,
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