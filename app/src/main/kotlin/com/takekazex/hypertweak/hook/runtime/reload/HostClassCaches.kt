package com.takekazex.hypertweak.hook.runtime.reload

import android.net.ConnectivityManager
import android.os.Binder
import android.util.LruCache
import com.takekazex.hypertweak.hook.base.HostMembers
import com.takekazex.hypertweak.util.logging.DebugLog

/**
 * Removes this generation's classes from host caches keyed by `Class`.
 *
 * `ConnectivityManager.sMethodFlagsCache` is a static LRU of `NetworkCallback` subclasses
 * (`AodSignalObserver`, `WifiPipelineSource`). Unregistering a callback leaves its class there, and
 * the strong `Class` key keeps the retired module loader and its DEX alive. Runs after every owner
 * has unregistered its callbacks; a removed entry is only recomputed if the class registers again.
 *
 * In system_server, binder latency sampling (`Binder.sObserver` → `BinderCallsStats.mLatencyObserver`)
 * keys its histograms by the served Binder class (`LatencyDims.mBinderClass`, e.g. the bar-window
 * bridge stub) until the next statsd push, hours later. Its entries are removed under the
 * observer's own `mLock`, which `callEnded` and `reset` hold around the same map.
 */
internal object HostClassCaches {
    fun purge() {
        val own = HostClassCaches::class.java.classLoader ?: return
        purgeMethodFlags(own)
        purgeBinderLatency(own)
    }

    private fun purgeMethodFlags(own: ClassLoader) {
        runCatching {
            val cache = HostMembers.declaredField(ConnectivityManager::class.java, "sMethodFlagsCache", accessible = true)
                ?.get(null) as? LruCache<*, *> ?: return
            @Suppress("UNCHECKED_CAST")
            val keys = (cache as LruCache<Any, Any>).snapshot().keys.filter { (it as? Class<*>)?.classLoader === own }
            keys.forEach { cache.remove(it) }
            if (keys.isNotEmpty()) DebugLog.d("HostClassCaches", "purged ConnectivityManager method flags=${keys.size}")
        }.onFailure { DebugLog.w("HostClassCaches", "ConnectivityManager method-flag purge failed", it) }
    }

    private fun purgeBinderLatency(own: ClassLoader) {
        runCatching {
            val stats = HostMembers.declaredField(Binder::class.java, "sObserver", accessible = true)?.get(null) ?: return
            val observer = HostMembers.read(stats, "mLatencyObserver") ?: return
            val lock = HostMembers.read(observer, "mLock") ?: return
            @Suppress("UNCHECKED_CAST")
            val histograms = HostMembers.read(observer, "mLatencyHistograms") as? MutableMap<Any?, Any?> ?: return
            val removed = synchronized(lock) {
                val keys = histograms.keys.filter { key ->
                    (key?.let { HostMembers.read(it, "mBinderClass") } as? Class<*>)?.classLoader === own
                }
                keys.forEach(histograms::remove)
                keys.size
            }
            if (removed > 0) DebugLog.d("HostClassCaches", "purged binder latency histograms=$removed")
        }.onFailure { DebugLog.w("HostClassCaches", "binder latency purge failed", it) }
    }
}
