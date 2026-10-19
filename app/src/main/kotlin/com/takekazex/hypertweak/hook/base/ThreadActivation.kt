package com.takekazex.hypertweak.hook.base

/** Scoped overrides must survive nested calls and release ownership even when the host throws. */
internal class ThreadActivation {
    // ThreadLocal.get() is annotated @RecentlyNullable on the platform; withInitial always supplies
    // a value, so the read is non-null once the initializer has run.
    private val depth = ThreadLocal.withInitial { 0 }
    private fun current(): Int = requireNotNull(depth.get())
    val active: Boolean get() = current() > 0
    fun <T> within(block: () -> T): T {
        val previous = current()
        depth.set(previous + 1)
        return try { block() } finally {
            if (previous == 0) depth.remove() else depth.set(previous)
        }
    }
}
