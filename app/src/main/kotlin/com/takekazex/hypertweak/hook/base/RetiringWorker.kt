package com.takekazex.hypertweak.hook.base

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A feature-owned worker. Closing is terminal; a new lifecycle creates a new owner. */
internal class RetiringWorker(name: String, idleSeconds: Long = 30) : AutoCloseable {
    private val executor = ThreadPoolExecutor(1, 1, idleSeconds, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(8), { task -> Thread(task, name).apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }
    @Volatile private var closed = false

    fun execute(action: () -> Unit): Boolean {
        if (closed) return false
        return try {
            executor.execute { if (!closed) action() }
            true
        } catch (_: RejectedExecutionException) { false }
    }

    override fun close() {
        closed = true
        executor.shutdownNow()
    }
}
