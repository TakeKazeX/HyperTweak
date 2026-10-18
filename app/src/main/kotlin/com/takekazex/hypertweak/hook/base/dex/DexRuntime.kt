package com.takekazex.hypertweak.hook.base.dex

import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.util.DebugLog
import org.luckypray.dexkit.DexKitBridge
import java.io.File

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

    /**
     * Resolves [feature] for [apkPath].
     *
     * [cacheOnly] answers from the cache without opening a backend (used by auxiliary processes
     * that must not miss ActivityManager's provider deadline). [cacheEmpty] records a legitimate
     * "no target on this build" result; otherwise an empty scan is not persisted.
     */
    fun <T> resolve(
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
        val store = DexCacheStore(feature, cacheDir)
        val started = System.nanoTime()
        val cached = store.read(apk)
        if (cached != null) {
            decode(cached, true)?.let { decoded ->
                DebugLog.d(
                    TAG,
                    "feature=${feature.id} cache hit values=${cached.size} in ${elapsed(started)}ms"
                )
                return decoded
            }
            DebugLog.d(TAG, "feature=${feature.id} cache did not materialize; rescanning")
        }
        if (cacheOnly) return null

        val values = runCatching {
            withSession(apkPath) { session -> compute(session) }
        }.onFailure {
            DebugLog.w(TAG, "feature=${feature.id} resolution failed", it)
        }.getOrNull() ?: return null
        val decoded = decode(values, false) ?: return null
        if (values.isNotEmpty() || cacheEmpty) store.write(apk, values)
        DebugLog.i(
            TAG,
            "feature=${feature.id} scanned in ${elapsed(started)}ms values=${values.size} " +
                "backends=${DexBackends.summary()}"
        )
        return decoded
    }

    /**
     * Runs [block] against the package's active session, reusing the dispatch-wide bridge opened by
     * [DexKitManager.beginSharedSession] when present. The bridge is closed when the outermost
     * session ends; [block] must materialize every reflective target it needs before returning.
     */
    fun <T> withSession(apkPath: String, block: (DexSession) -> T): T? {
        DexKitManager.currentBridge(apkPath)?.let { bridge ->
            return block(DexSession(DexBackendId.DEXKIT, apkPath, bridge))
        }
        val scope = DexKitManager.beginSharedSession(apkPath)
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
