package com.takekazex.hypertweak.hook.runtime.reload

import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers

/**
 * Backstop for this generation's bundled kotlinx scheduler on hot reload.
 *
 * Hook code dispatches on `HookDispatchers.IO`, which needs no teardown, so normally the scheduler
 * was never started and nothing happens here. If something (a library, or code that slipped past
 * `check_architecture.py`) did start it, its never-terminating workers would pin the generation.
 * Only then it is shut down, from main after everything already queued there, and after a grace
 * period: a terminated scheduler throws on every later dispatch, on whichever thread resumes the
 * coroutine. The INFO log names the case so the remaining user can be found. Shutdown joins busy
 * workers, so it never runs on main itself.
 */
internal object ModuleDispatchers {
    private const val TAG = "ModuleDispatchers"
    private const val GRACE_MS = 15_000L
    // `CoroutineScheduler` names its workers after the scheduler; class names are obfuscated.
    private const val WORKER_PREFIX = "DefaultDispatcher-worker-"

    fun retire() {
        if (!schedulerStarted()) return
        DebugLog.i(TAG, "kotlinx scheduler started in this generation; shutting it down after grace")
        val start = Runnable {
            Thread(::shutdownAfterGrace, "HyperTweak-RetireDispatchers").apply {
                isDaemon = true
                start()
            }
        }
        val queued = Looper.getMainLooper()?.let { Handler(it).post(start) } == true
        if (!queued) start.run()
    }

    /** A worker of this generation's own scheduler; the host's kotlinx lives in another loader. */
    private fun schedulerStarted(): Boolean = runCatching {
        val own = Dispatchers::class.java.classLoader
        liveThreads().any { thread -> thread.name.startsWith(WORKER_PREFIX) && thread.javaClass.classLoader === own }
    }.onFailure { DebugLog.w(TAG, "kotlinx scheduler probe failed", it) }.getOrDefault(true)

    /** Every live thread, without capturing stacks (system_server runs hundreds of threads). */
    private fun liveThreads(): List<Thread> {
        var root = Thread.currentThread().threadGroup ?: return emptyList()
        while (true) root = root.parent ?: break
        var threads = arrayOfNulls<Thread>(root.activeCount() + 16)
        var count = root.enumerate(threads, true)
        while (count == threads.size) {
            threads = arrayOfNulls(threads.size * 2)
            count = root.enumerate(threads, true)
        }
        return threads.take(count).filterNotNull()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun shutdownAfterGrace() {
        try { Thread.sleep(GRACE_MS) } catch (_: InterruptedException) { }
        runCatching { Dispatchers.shutdown() }
            .onFailure { DebugLog.w(TAG, "retired generation dispatcher shutdown failed", it) }
    }
}
