package com.takekazex.hypertweak.hook.base

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Host/framework objects only; no module generation is stored in the host. */
internal object HostAccess {
    private val main = Handler(Looper.getMainLooper())

    fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        val state = java.util.concurrent.atomic.AtomicInteger(0)
        val task = Runnable {
            if (!state.compareAndSet(0, 1)) return@Runnable
            try { result = runCatching(action) } finally { done.countDown() }
        }
        check(main.post(task)) { "Host main looper is unavailable" }
        if (!done.await(5, TimeUnit.SECONDS)) {
            state.compareAndSet(0, 2)
            main.removeCallbacks(task)
            error("Host main-thread action timed out; an already running action may finish")
        }
        return checkNotNull(result).getOrThrow()
    }

    fun field(type: Class<*>, name: String) = HostMembers.field(type, name)
    fun read(owner: Any, name: String) = HostMembers.read(owner, name)
    fun method(type: Class<*>, name: String, vararg parameters: Class<*>) = HostMembers.method(type, name, *parameters)
    fun method(owner: Any, name: String, vararg parameters: Class<*>) = HostMembers.method(owner, name, *parameters)

    fun ancestor(view: android.view.View, className: String, includeSelf: Boolean = true): android.view.View? {
        var current: android.view.View? = if (includeSelf) view else view.parent as? android.view.View
        while (current != null) {
            if (current.javaClass.name == className) return current
            current = current.parent as? android.view.View
        }
        return null
    }

    fun invoke(owner: Any, name: String): Any? = method(owner, name)?.invoke(owner)
    fun provider(component: Any, name: String): Any? = read(component, name)?.let { invoke(it, "get") }

}
