package com.takekazex.hypertweak.hook

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Queue deadlines may reject unstarted work; a started transaction needs its actual receipt. */
internal object AcknowledgedSettingsTask {
    fun <T> run(executor: ExecutorService, queueTimeoutMillis: Long = 5_000, commit: () -> T): T {
        val state = AtomicInteger(0) // queued -> running (1) or cancelled (2)
        val task = executor.submit<T> {
            check(state.compareAndSet(0, 1)) { "settings transaction cancelled before start" }
            commit()
        }
        var interrupted = false
        try {
            try { return task.get(queueTimeoutMillis, TimeUnit.MILLISECONDS) }
            catch (error: Exception) {
                if (error !is TimeoutException && error !is InterruptedException) throw error
                interrupted = error is InterruptedException
                if (state.compareAndSet(0, 2)) {
                    task.cancel(false)
                    throw error
                }
            }
            // Binder commit cannot be undone by cancelling a Future. Keep the caller's mutation
            // lock until the receipt arrives so mirror updates and subsequent writes stay ordered.
            while (true) {
                try { return task.get() }
                catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
