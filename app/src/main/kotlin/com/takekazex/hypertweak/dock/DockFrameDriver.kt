package com.takekazex.hypertweak.dock

import android.os.Handler
import android.view.Choreographer
import com.takekazex.hypertweak.util.DebugLog
import java.util.concurrent.atomic.AtomicBoolean

/** One owner-thread reconciliation per vsync, regardless of how many native events arrive. */
internal class DockFrameDriver(private val owner: Handler, private val render: () -> Unit) : AutoCloseable {
    private val retired = AtomicBoolean()
    private val posted = AtomicBoolean()
    private var clock: Choreographer? = null // Owner thread only.
    private var scheduled = false
    private val frame = Choreographer.FrameCallback {
        scheduled = false
        if (!retired.get()) runCatching(render)
            .onFailure { DebugLog.w("DockBackground", "frame dispatch failed", it) }
    }
    private val dispatch = Runnable {
        posted.set(false)
        if (!retired.get() && !scheduled) {
            scheduled = true
            (clock ?: Choreographer.getInstance().also { clock = it }).postFrameCallback(frame)
        }
    }

    fun request() {
        if (retired.get() || !posted.compareAndSet(false, true)) return
        if (!owner.post(dispatch)) posted.set(false)
    }

    override fun close() {
        if (!retired.compareAndSet(false, true)) return
        owner.removeCallbacks(dispatch)
        posted.set(false)
        owner.post {
            clock?.removeFrameCallback(frame)
            clock = null
            scheduled = false
        }
    }
}
