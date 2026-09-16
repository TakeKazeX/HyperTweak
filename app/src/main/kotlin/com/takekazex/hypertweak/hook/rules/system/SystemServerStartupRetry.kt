package com.takekazex.hypertweak.hook.rules.system

import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.util.DebugLog

/** Retries a system_server operation that can race ContentResolver service startup. */
internal class SystemServerStartupRetry(
    private val scope: String,
    private val action: () -> Boolean,
    private val maxAttempts: Int = 20,
    private val delayMs: Long = 500L
) {
    private val handler = Handler(Looper.getMainLooper())

    private var generation = 0L
    private var attempt = 0
    private var pending: Runnable? = null

    @Synchronized
    fun schedule() {
        if (pending != null) return
        val token = ++generation
        attempt = 0
        post(token)
    }

    @Synchronized
    fun cancel() {
        generation++
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    private fun post(token: Long) {
        val task = Runnable { run(token) }
        pending = task
        handler.postDelayed(task, delayMs)
    }

    private fun run(token: Long) {
        synchronized(this) {
            if (token != generation || pending == null) return
            pending = null
        }

        val ready = runCatching { action() }.getOrDefault(false)
        if (ready) return

        synchronized(this) {
            if (token != generation) return
            attempt++
            if (attempt >= maxAttempts) {
                DebugLog.w(scope, "system-server startup retry exhausted attempts=$attempt")
                return
            }
            post(token)
        }
    }
}
