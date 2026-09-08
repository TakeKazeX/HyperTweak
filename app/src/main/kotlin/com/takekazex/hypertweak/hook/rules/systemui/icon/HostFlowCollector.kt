package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.util.DebugLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * Small boundary around SystemUI's JavaAdapter flow helper.
 *
 * SystemUI contains its own coroutine classes, so the scope and flow are deliberately kept as
 * opaque host objects. The static three-argument overload is used because the instance overload
 * returns void and loses the cancellation handle. Values are serialized onto the host main
 * looper before the module callback runs; callers can additionally reject stale generations.
 */
object HostFlowCollector {
    private const val TAG = "IconTuner"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cacheLock = Any()

    @Volatile
    private var cachedLoader: ClassLoader? = null

    @Volatile
    private var cachedCollectMethod: Method? = null

    /** A cancellation handle is returned only after the host helper successfully starts. */
    class Handle internal constructor(private val job: Any) {
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            runCatching {
                val method = job.javaClass.methods.firstOrNull {
                    it.name == "cancel" && (it.parameterTypes.isEmpty() || it.parameterTypes.size == 1)
                } ?: error("Job.cancel method not found")
                method.isAccessible = true
                if (method.parameterTypes.isEmpty()) {
                    method.invoke(job)
                } else {
                    // Kotlin's Job.cancel is exposed to Java as a nullable Throwable overload on
                    // some coroutine revisions and as a no-arg bridge on others.
                    method.invoke(job, *arrayOfNulls<Any>(1))
                }
            }.onFailure { DebugLog.w(TAG, "host flow cancellation failed", it) }
        }
    }

    /**
     * Starts a host collection and returns its Job wrapper. A failed lookup, invocation, or null
     * host return is a failure and is never recorded as an active subscription.
     */
    fun collect(
        scope: Any?,
        flow: Any?,
        consumer: (Any?) -> Unit,
        isCurrent: () -> Boolean = { true }
    ): Handle? {
        if (scope == null || flow == null) return null
        val method = resolveCollectMethod(scope, flow) ?: return null
        val guardedConsumer = Consumer<Any?> { value ->
            val deliver = Runnable {
                runCatching {
                    if (isCurrent()) consumer(value)
                }.onFailure { DebugLog.w(TAG, "host flow subscriber failed", it) }
            }
            runCatching {
                if (Looper.myLooper() == Looper.getMainLooper()) deliver.run()
                else mainHandler.post(deliver)
            }.onFailure { DebugLog.w(TAG, "host flow main dispatch failed", it) }
        }
        return runCatching {
            method.invoke(null, scope, flow, guardedConsumer)
        }.mapCatching { job ->
            if (job == null) error("JavaAdapter returned null Job")
            Handle(job)
        }.onFailure {
            DebugLog.w(TAG, "static JavaAdapter flow collection failed", it)
        }.getOrNull()
    }

    /** Drops cached host reflection when a hot-reload generation retires. */
    fun resetForReload() {
        synchronized(cacheLock) {
            cachedLoader = null
            cachedCollectMethod = null
        }
    }

    private fun resolveCollectMethod(scope: Any, flow: Any): Method? {
        val loader = scope.javaClass.classLoader
            ?: flow.javaClass.classLoader
            ?: HostFlowCollector::class.java.classLoader
        val cached = cachedCollectMethod
        if (cached != null && cachedLoader === loader) return cached

        synchronized(cacheLock) {
            val secondLook = cachedCollectMethod
            if (secondLook != null && cachedLoader === loader) return secondLook
            val adapterClass = runCatching {
                Class.forName(
                    IconTunerFlows.hostClassName("com.android.systemui.util", "kotlin.JavaAdapter"),
                    false,
                    loader
                )
            }.getOrNull() ?: run {
                DebugLog.hookSkipped(TAG, "SystemUI JavaAdapter", "class not found")
                return null
            }
            val method = adapterClass.methods.firstOrNull { candidate ->
                Modifier.isStatic(candidate.modifiers) &&
                    candidate.name == "alwaysCollectFlow" &&
                    candidate.parameterTypes.size == 3 &&
                    candidate.parameterTypes[0].isAssignableFrom(scope.javaClass) &&
                    candidate.parameterTypes[1].isAssignableFrom(flow.javaClass) &&
                    candidate.parameterTypes[2] == Consumer::class.java &&
                    candidate.returnType != Void.TYPE
            }?.apply { isAccessible = true }
            if (method == null) {
                DebugLog.hookSkipped(TAG, "JavaAdapter#alwaysCollectFlow(scope,flow,consumer)", "method not found")
                return null
            }
            cachedLoader = loader
            cachedCollectMethod = method
            return method
        }
    }
}
