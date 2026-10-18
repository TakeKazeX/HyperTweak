package com.takekazex.hypertweak.hook.base.dex

import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.util.DebugLog
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
 * file so an external engine can produce the same format. Mirrors the hot-reload layer owning
 * hooker lifecycle instead of every hooker owning its own bridge.
 */
internal object DexRuntime {
    private const val TAG = "DexRuntime"
    private val resolutions = DexResolution()
    private val identities = ConcurrentHashMap<String, DexKitCacheIdentity>()
    private val scopes = DexScopes(DexKitManager::beginSharedSession)
    val isResolving: Boolean get() = DexKitManager.hasActiveUsers
    private val worker = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(32), { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "HT-DexPrepare").apply { isDaemon = true }
    }).apply { allowCoreThreadTimeOut(true) }

    fun beginSession(apkPath: String): AutoCloseable = scopes.begin(apkPath)

    /** The executing package owns caches, including queries against plugin APKs. */
    fun cacheDirectory(info: ApplicationInfo?): File? =
        (info?.deviceProtectedDataDir?.takeIf(String::isNotBlank)
            ?: info?.dataDir?.takeIf(String::isNotBlank))?.let { File(it, "cache") }

    fun <T> withScope(apkPath: String?, block: () -> T): T {
        val scope = apkPath?.let(::beginSession)
        return try { block() } finally { scope?.close() }
    }

    /** Detached operand indexes share the dispatch lifetime, then release together. */
    fun <T : Any> sourceIndex(apkPath: String, type: Class<T>, create: () -> T): T {
        val apk = File(apkPath)
        return scopes.artifact(apkPath, type, "$apkPath:${apk.lastModified()}:${apk.length()}", create)
    }

    fun preparation(
        publish: (() -> Unit) -> Unit,
        failure: (Throwable) -> Unit,
        afterStartup: (() -> Unit) -> Unit = { it() },
    ): DexPreparation = DexPreparation(
        background = { worker.execute(it) }, publish = publish, failure = failure, afterStartup = afterStartup,
    )

    /** Compatibility queries share the same session and timing boundary; no bridge escapes. */
    fun <T> query(apkPath: String, feature: String, block: (DexKitBridge) -> T): T? {
        val started = System.nanoTime()
        return try { withSession(apkPath) { it.dexKit(block) } } finally {
            val duration = elapsed(started)
            if (duration >= 250L) DebugLog.i(TAG,
                "query=$feature durationMs=$duration thread=${Thread.currentThread().name}")
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
    ): Map<String, Class<*>> {
        val scope = beginSession(apkPath)
        return try {
            buildMap {
                queries.forEach { (key, query) ->
                    val feature = object : DexFeature {
                        override val id = "class.$key"
                        override val schema = 1
                    }
                    val target = resolve(feature, apkPath, cacheDir,
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
                    else if (logMissingQueries) DebugLog.w(TAG, "class query=$key unavailable; dependent hook skipped")
                }
            }
        } finally { scope.close() }
    }

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
        compute: (DexSession) -> Map<String, String>,
        decode: (Map<String, String>, Boolean) -> T?,
    ): T? {
        val apk = File(apkPath)
        if (!apk.isFile) return null
        val identity = cacheIdentity(apk) ?: return null
        val store = DexCacheStore(feature, cacheDir)
        val started = System.nanoTime()
        var backend = DexBackendId.DEXKIT
        return resolutions.resolve(
            key = "${feature.id}:${feature.schema}:$apkPath:${identity.sha256}",
            cacheOnly = cacheOnly,
            read = { store.read(apk, identity) },
            scan = {
                runCatching {
                    withSession(apkPath) { session -> backend = session.backend; compute(session) }
                }.onFailure { DebugLog.w(TAG, "feature=${feature.id} resolution failed", it) }.getOrNull()
            },
            decode = { values, cached ->
                decode(values, cached)?.also {
                    DebugLog.i(TAG, "feature=${feature.id} ${if (cached) "cache hit" else "scanned"} " +
                        "in ${elapsed(started)}ms values=${values.size} thread=${Thread.currentThread().name}")
                }
            },
            write = { values ->
                if (values.isNotEmpty() || cacheEmpty) store.write(apk, values, identity, backend)
            },
            invalid = { DebugLog.w(TAG, "feature=${feature.id} descriptors rejected", it) },
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

    /** Installed APK paths are immutable; metadata guards one full digest per process/build. */
    private fun cacheIdentity(file: File): DexKitCacheIdentity? {
        identities[file.absolutePath]?.let { cached ->
            if (cached.modified == file.lastModified() && cached.size == file.length()) return cached
        }
        return DexKitCacheIdentity.capture(file)?.also { identities[file.absolutePath] = it }
    }

    private fun elapsed(started: Long): Long = (System.nanoTime() - started) / 1_000_000L
}
