package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Host/framework objects only; no module generation is stored in the host. */
internal object StatusIconHostAccess {
    private val main = Handler(Looper.getMainLooper())

    fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        val task = Runnable {
            try { result = runCatching(action) } finally { done.countDown() }
        }
        check(main.post(task)) { "SystemUI main looper is unavailable" }
        if (!done.await(5, TimeUnit.SECONDS)) {
            main.removeCallbacks(task)
            error("Status icon main-thread restoration timed out")
        }
        return checkNotNull(result).getOrThrow()
    }

    fun read(owner: Any, name: String): Any? = generateSequence(owner.javaClass) { it.superclass }
        .mapNotNull { type -> runCatching { type.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }
        .firstOrNull()?.get(owner)

    fun method(owner: Any, name: String, vararg parameters: Class<*>): Method? =
        generateSequence(owner.javaClass) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredMethod(name, *parameters).apply { isAccessible = true } }.getOrNull() }
            .firstOrNull()

    fun invoke(owner: Any, name: String): Any? = method(owner, name)?.invoke(owner)
    fun provider(component: Any, name: String): Any? = read(component, name)?.let { invoke(it, "get") }
}
