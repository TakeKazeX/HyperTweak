package com.takekazex.hypertweak.hook.rules.systemui.island.policy

import com.takekazex.hypertweak.hook.rules.systemui.island.model.MediaReturnTarget

/** Render projections never leak into a nested coordinator scheduling transaction. */
internal class IslandRenderScope {
    @PublishedApi internal val active = ThreadLocal<Any?>()
    private val returnPlan = ThreadLocal<MediaReturnTarget?>()
    fun returnTarget(): MediaReturnTarget? = returnPlan.get()
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

    /** Pin one immutable target across nested callbacks and concurrent Binder queries. */
    fun <T> render(view: Any, target: MediaReturnTarget?, block: () -> T): T {
        val previous = returnPlan.get()
        returnPlan.set(target)
        try { return render(view, block) } finally {
            if (previous == null) returnPlan.remove() else returnPlan.set(previous)
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
