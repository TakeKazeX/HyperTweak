package com.takekazex.hypertweak.hook.base

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Background coroutine dispatch for code running inside host processes. Use [IO] instead of
 * `Dispatchers.IO`/`Default`, which `check_architecture.py` rejects in hook code.
 *
 * Every generation carries its own kotlinx copy. Its scheduler never terminates core-pool workers,
 * and a live thread is a GC root that pins the generation's module loader and in-memory DEX for the
 * life of the host. Terminating it is not safe either: a terminated scheduler throws on every later
 * dispatch, on whichever thread resumes the coroutine, so a late resume of a retiring generation on
 * the host main thread crashes the host.
 *
 * [IO] is a plain pool whose threads all time out when idle. It is never shut down, registers no JDK
 * Cleaner action and holds no thread once its work drains, so a retired generation needs no teardown
 * and a late dispatch just starts a short-lived thread. Lifetime of the work itself still belongs to
 * a cancellable scope, normally [BaseHooker.generationScope].
 */
object HookDispatchers {
    private const val THREADS = 4
    private const val IDLE_MS = 10_000L
    private val created = AtomicInteger()

    private val pool = ThreadPoolExecutor(
        THREADS, THREADS, IDLE_MS, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ) { task ->
        Thread(task, "HyperTweak-IO-${created.incrementAndGet()}").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    @JvmField val IO: CoroutineDispatcher = pool.asCoroutineDispatcher()
}
