package com.takekazex.hypertweak.hook.base

/** Scoped overrides must survive nested calls and release ownership even when the host throws. */
internal class ThreadActivation {
    private val depth = ThreadLocal.withInitial { 0 }
    val active: Boolean get() = depth.get() > 0
    fun <T> within(block: () -> T): T {
        val previous = depth.get()
        depth.set(previous + 1)
        return try { block() } finally {
            if (previous == 0) depth.remove() else depth.set(previous)
        }
    }
}
