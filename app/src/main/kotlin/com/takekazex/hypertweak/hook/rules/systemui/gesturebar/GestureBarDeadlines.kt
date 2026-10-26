package com.takekazex.hypertweak.hook.rules.systemui.gesturebar

import android.os.Handler

/** One replaceable deadline per display; owned and drained by the current hook generation. */
internal class GestureBarDeadlines(
    private val state: GestureBarState,
    private val handler: () -> Handler,
    private val scheduler: GestureBarScheduler = HandlerGestureBarScheduler(handler),
    private val onDeadline: (Int) -> Unit,
) {
    private val deadlines = HashMap<Int, PendingDeadline>()
    /**
     * Keeps one callback per display in step with the current phase's deadline. A phase change can
     * move the deadline in either direction (leaving -> hidden extends it, hidden -> returning
     * shortens it), so an already-queued callback is replaced rather than kept.
     */
    fun scheduleDeadline(display: Int) {
        val at = state.deadlineFor(display)
        if (at == 0L) { cancelDeadline(display); return }
        val existing = synchronized(deadlines) { deadlines[display] }
        if (existing != null && existing.at == at && scheduler.contains(existing.task)) return
        cancelDeadline(display)
        val runnable = Runnable {
            synchronized(deadlines) { deadlines.remove(display) }
            onDeadline(display)
        }
        synchronized(deadlines) { deadlines[display] = PendingDeadline(at, runnable) }
        scheduler.post(runnable, (at - scheduler.now()).coerceAtLeast(0L))
    }

    fun cancelDeadline(display: Int) {
        synchronized(deadlines) { deadlines.remove(display) }?.let { scheduler.remove(it.task) }
    }

    fun cancelAllDeadlines() {
        val pending = synchronized(deadlines) {
            deadlines.values.toList().also { deadlines.clear() }
        }
        pending.forEach { scheduler.remove(it.task) }
    }

    private class PendingDeadline(val at: Long, val task: Runnable)

}
