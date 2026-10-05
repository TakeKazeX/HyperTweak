package com.takekazex.hypertweak.hook.rules.systemui.icon

/** Cross-thread requests share one frame; retirement invalidates already dispatched callbacks. */
internal class FrameUpdateGate {
    private var sequence = 0L
    private var pending: Long? = null

    @Synchronized fun request(): Long? {
        if (pending != null) return null
        return (++sequence).also { pending = it }
    }

    @Synchronized fun isPending(ticket: Long): Boolean = pending == ticket

    @Synchronized fun cancel() {
        pending = null
        sequence++
    }

    fun drain(ticket: Long, render: () -> Unit) {
        val accepted = synchronized(this) {
            if (pending != ticket) false else { pending = null; true }
        }
        if (accepted) render()
    }
}
