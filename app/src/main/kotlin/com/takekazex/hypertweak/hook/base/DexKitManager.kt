package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.util.logging.DebugLog
import org.luckypray.dexkit.DexKitBridge
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object DexKitManager {
    private val bridgeLock = ReentrantLock()
    private val bridgeDrained = bridgeLock.newCondition()

    /**
     * Dispatch-wide scope shared by every DexKit consumer in one package's hook installation.
     *
     * The bridge opens lazily on the first [withBridge] call, so a package that never queries DEX
     * pays nothing, while every consumer in the same dispatch reuses one native handle. The scope
     * is thread-local because a deferred resolver in an auxiliary process must not borrow another
     * thread's live bridge.
     */
    private class SharedScope(val apkPath: String) {
        var bridge: DexKitBridge? = null
        var active = false
        var failed = false
    }

    private val sharedScopes: ThreadLocal<ArrayList<SharedScope>> =
        ThreadLocal.withInitial { ArrayList() }

    private fun scopeStack(): ArrayList<SharedScope> =
        sharedScopes.get() ?: ArrayList<SharedScope>().also { sharedScopes.set(it) }

    @Volatile
    private var isLoaded = false

    @Volatile
    private var activeBridgeUsers = 0

    internal val hasActiveUsers: Boolean
        get() = activeBridgeUsers != 0

    @Volatile
    private var hotReloadPreparing = false

    fun loadLibrary(): Boolean {
        if (hotReloadPreparing) {
            DebugLog.d("DexKit", "skip loading native library during hot reload preparation")
            return false
        }
        bridgeLock.withLock {
            if (hotReloadPreparing) {
                DebugLog.d("DexKit", "skip loading native library during hot reload preparation")
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

            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (activeBridgeUsers > 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                runCatching { bridgeDrained.awaitNanos(remaining) }
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
        val session = beginSharedSession(apkPath)
        return try {
            val scope = scopeStack().last { it.apkPath == apkPath }
            val bridge = sharedBridge(scope) ?: return null
            runCatching { block(bridge) }.onFailure { t ->
                DebugLog.e("DexKit", "failed to run shared bridge block for APK $apkPath", t)
            }.getOrNull()
        } finally { session.close() }
    }

    /** Bridge currently open on this thread for [apkPath], if a shared scope opened one. */
    fun currentBridge(apkPath: String): DexKitBridge? =
        scopeStack().lastOrNull { it.apkPath == apkPath }?.bridge

    /**
     * Opens a dispatch-wide scope; [withBridge] lazily creates its single bridge.
     *
     * Nested scopes for the same APK return a no-op handle so the outermost owner closes it.
     * [withBridge] callers outside any scope keep the previous one-bridge-per-call behavior.
     */
    fun beginSharedSession(apkPath: String): AutoCloseable {
        val stack = scopeStack()
        if (stack.any { it.apkPath == apkPath }) return AutoCloseable { }
        val scope = SharedScope(apkPath)
        stack.add(scope)
        return AutoCloseable {
            stack.remove(scope)
            closeScope(scope)
        }
    }

    private fun sharedBridge(scope: SharedScope): DexKitBridge? {
        scope.bridge?.let { return it }
        if (scope.failed) return null
        if (!enterBridgeSession()) return null
        scope.active = true
        val started = System.nanoTime()
        try {
            if (!loadLibrary()) {
                scope.failed = true
                return null
            }
            val created = runCatching { DexKitBridge.create(scope.apkPath) }.onFailure { t ->
                DebugLog.e("DexKit", "failed to create shared bridge for APK ${scope.apkPath}", t)
            }.getOrNull()
            if (created == null) {
                scope.failed = true
                return null
            }
            scope.bridge = created
            DebugLog.i(
                "DexKit",
                "shared bridge opened in ${(System.nanoTime() - started) / 1_000_000L}ms for ${scope.apkPath}"
            )
            return created
        } finally {
            if (scope.bridge == null) {
                scope.active = false
                exitBridgeSession()
            }
        }
    }

    private fun closeScope(scope: SharedScope) {
        runCatching { scope.bridge?.close() }
        scope.bridge = null
        if (scope.active) {
            scope.active = false
            exitBridgeSession()
        }
    }

}
