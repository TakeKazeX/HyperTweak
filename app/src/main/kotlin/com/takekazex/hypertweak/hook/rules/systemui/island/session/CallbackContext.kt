package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.model.SchedulingTransaction

/**
 * The single ThreadLocal container for host callback context. Every scope restores the previous
 * value so a nested native callback cannot inherit a stale owner or transaction.
 */
internal class CallbackContext {
    private class Frame {
        var transaction: SchedulingTransaction? = null
        var measuringView: Any? = null
        var animatingView: Any? = null
        var renderingView: Any? = null

        fun clear() {
            transaction = null
            measuringView = null
            animatingView = null
            renderingView = null
        }
    }

    private val local = ThreadLocal<Frame?>()

    val transaction: SchedulingTransaction? get() = local.get()?.transaction
    val measuringView: Any? get() = local.get()?.measuringView
    val animatingView: Any? get() = local.get()?.animatingView
    val renderingView: Any? get() = local.get()?.renderingView

    fun setTransaction(transaction: SchedulingTransaction?) {
        val frame = frame()
        frame.transaction = transaction
        if (frame.isEmpty()) local.remove()
    }

    fun <T> withTransaction(transaction: SchedulingTransaction?, block: () -> T): T =
        scoped(block) { it.transaction = transaction }

    fun <T> withMeasuring(view: Any?, block: () -> T): T =
        scoped(block) { it.measuringView = view }

    fun <T> withAnimating(view: Any?, block: () -> T): T =
        scoped(block) { it.animatingView = view }

    fun <T> withRendering(view: Any?, block: () -> T): T =
        scoped(block) { it.renderingView = view }

    fun clearIfIdle() {
        local.get()?.let { if (it.isEmpty()) local.remove() }
    }

    private fun frame(): Frame = local.get() ?: Frame().also { local.set(it) }

    private inline fun <T> scoped(block: () -> T, change: (Frame) -> Unit): T {
        val frame = frame()
        val previous = Snapshot(frame)
        change(frame)
        try {
            return block()
        } finally {
            previous.restore(frame)
            if (frame.isEmpty()) local.remove()
        }
    }

    private class Snapshot(frame: Frame) {
        private val transaction = frame.transaction
        private val measuring = frame.measuringView
        private val animating = frame.animatingView
        private val rendering = frame.renderingView

        fun restore(frame: Frame) {
            frame.transaction = transaction
            frame.measuringView = measuring
            frame.animatingView = animating
            frame.renderingView = rendering
        }
    }

    private fun Frame.isEmpty(): Boolean =
        transaction == null && measuringView == null && animatingView == null && renderingView == null
}
