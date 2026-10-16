package com.takekazex.hypertweak.hook.rules.systemui.island

/** Render projections never leak into a nested coordinator scheduling transaction. */
internal class IslandRenderScope {
    @PublishedApi internal val active = ThreadLocal<Any?>()
    fun get(): Any? = active.get()
    fun set(value: Any?) = active.set(value)
    fun remove() = active.remove()

    fun <T> render(view: Any, block: () -> T): T {
        val previous = active.get()
        active.set(view)
        try { return block() } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }

    inline fun <T> native(block: () -> T): T {
        val previous = active.get()
        active.remove()
        try { return block() } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }
}
