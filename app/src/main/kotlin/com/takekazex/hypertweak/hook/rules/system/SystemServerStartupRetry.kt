package com.takekazex.hypertweak.hook.rules.system

import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.ConcurrentHashMap

internal interface RecoveryScheduler {
    fun post(task: Runnable, delay: Long): Boolean
    fun remove(task: Runnable)
}
private class MainRecoveryScheduler : RecoveryScheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    override fun post(task: Runnable, delay: Long) = handler.postDelayed(task, delay)
    override fun remove(task: Runnable) = handler.removeCallbacks(task)
}

/** Finite polling windows, rearmed by framework lifecycle events while the operation is pending. */
internal class SystemServerStartupRetry(
    private val scope: String,
    private val action: () -> Boolean,
    private val maxAttempts: Int = 20,
    private val delayMs: Long = 500L,
    private val scheduler: RecoveryScheduler = MainRecoveryScheduler(),
    private val report: (String, Throwable?) -> Unit = { message, error -> DebugLog.w(scope, message, error) },
    private val recovered: (Int) -> Unit = { count -> DebugLog.i(scope, "system settings alignment and observers ready attempts=$count") }
) {
    private var generation = 0L
    private var attempt = 0
    private var pending: Runnable? = null

    @Synchronized fun schedule() {
        if (pending != null) return
        active.add(this)
        attempt = 0
        post(++generation, delayMs)
    }
    @Synchronized fun cancel() {
        generation++
        pending?.let(scheduler::remove)
        pending = null
        active.remove(this)
    }
    @Synchronized private fun onLifecycleEvent() {
        if (!active.contains(this)) return
        pending?.let(scheduler::remove)
        pending = null
        attempt = 0
        post(++generation, 0L)
    }
    private fun post(token: Long, delay: Long) {
        val task = Runnable { run(token) }
        pending = task
        if (!scheduler.post(task, delay)) {
            pending = null
            report("system settings retry scheduling rejected; awaiting lifecycle event", null)
        }
    }
    private fun run(token: Long) {
        synchronized(this) {
            if (token != generation || pending == null) return
            pending = null
        }
        val ready = runCatching(action).onFailure {
            report("system settings recovery attempt failed", it)
        }.getOrDefault(false)
        synchronized(this) {
            if (token != generation) return
            attempt++
            if (ready) {
                active.remove(this)
                recovered(attempt)
            } else if (attempt >= maxAttempts) {
                report("system settings not ready after attempts=$attempt; awaiting framework lifecycle event", null)
            } else post(token, delayMs)
        }
    }
    companion object {
        private val active = ConcurrentHashMap.newKeySet<SystemServerStartupRetry>()
        fun onLifecycleEvent() = active.toList().forEach { it.onLifecycleEvent() }
        fun cancelAll() = active.toList().forEach { it.cancel() }
    }
}
