package com.takekazex.hypertweak.hook.base

import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method
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

    private data class MethodKey(val name: String, val parameters: List<Class<*>>)
    private class Members {
        val fields = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<java.lang.reflect.Field>>()
        val methods = java.util.concurrent.ConcurrentHashMap<MethodKey, java.util.Optional<Method>>()
    }
    // ClassValue follows the actual defining loader and cannot pin it through a global Class map.
    private val members = object : ClassValue<Members>() {
        override fun computeValue(type: Class<*>): Members = Members()
    }
    fun read(owner: Any, name: String): Any? = members.get(owner.javaClass).fields.computeIfAbsent(name) {
        java.util.Optional.ofNullable(generateSequence(owner.javaClass) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }
            .firstOrNull())
    }.orElse(null)?.get(owner)

    fun method(owner: Any, name: String, vararg parameters: Class<*>): Method? =
        members.get(owner.javaClass).methods.computeIfAbsent(MethodKey(name, parameters.toList())) {
            java.util.Optional.ofNullable(generateSequence(owner.javaClass) { it.superclass }
                .mapNotNull { type -> runCatching { type.getDeclaredMethod(name, *parameters).apply { isAccessible = true } }.getOrNull() }
                .firstOrNull())
        }.orElse(null)

    fun invoke(owner: Any, name: String): Any? = method(owner, name)?.invoke(owner)
    fun provider(component: Any, name: String): Any? = read(component, name)?.let { invoke(it, "get") }

}
