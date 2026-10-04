package com.takekazex.hypertweak.hook

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One request in flight; synchronous, duplicate and late Binder callbacks are safe. */
internal class HotReloadBatch<T, R : Any>(
    private val targets: List<T>,
    private val request: (T, (R) -> Unit) -> Unit,
    private val failure: (T, Throwable) -> R,
    private val finished: (List<R>) -> Unit
) {
    private val lock = Any()
    private val draining = AtomicInteger()
    private val results = ArrayList<R>()
    private var index = 0
    private var inFlight = false
    private var stopped = false

    fun start() = drain()
    fun cancel() { synchronized(lock) { stopped = true } }

    private fun drain() {
        if (draining.getAndIncrement() != 0) return
        do {
            var completion: List<R>? = null
            val next = synchronized(lock) {
                when {
                    stopped || inFlight -> null
                    index == targets.size -> {
                        stopped = true
                        completion = results.toList()
                        null
                    }
                    else -> { inFlight = true; targets[index++] }
                }
            }
            completion?.let(finished)
            if (next != null) {
                val delivered = AtomicBoolean()
                val accept: (R) -> Unit = { result ->
                    if (delivered.compareAndSet(false, true)) {
                        val accepted = synchronized(lock) {
                            if (stopped) false else {
                                results += result
                                inFlight = false
                                true
                            }
                        }
                        if (accepted) drain()
                    }
                }
                try { request(next, accept) } catch (t: Throwable) { accept(failure(next, t)) }
            }
        } while (draining.decrementAndGet() != 0)
    }
}
