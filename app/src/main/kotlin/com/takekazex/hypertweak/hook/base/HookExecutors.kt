package com.takekazex.hypertweak.hook.base

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Executors for code running inside host processes. Use instead of `Executors.new*`, which
 * `check_architecture.py` rejects in hook code.
 *
 * An `Executors` wrapper registers a JDK Cleaner action that references the executor and its module
 * `ThreadFactory`, and its default threads are non-daemon core threads that never time out. Either
 * one pins the whole module generation when the executor is not shut down, for example when a late
 * callback of a retiring generation lazily creates its owner again. These executors register no
 * Cleaner action and their threads exit when idle, so an executor that is never shut down pins
 * nothing once its work drains. Shutdown and rejection behave as for `newSingleThreadExecutor`.
 */
object HookExecutors {
    private const val IDLE_SECONDS = 10L

    /** One daemon thread, FIFO, unbounded queue; the thread exits after [IDLE_SECONDS] idle. */
    @JvmStatic
    fun serial(name: String): ExecutorService = ThreadPoolExecutor(
        1, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { task -> Thread(task, name).apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }
}
