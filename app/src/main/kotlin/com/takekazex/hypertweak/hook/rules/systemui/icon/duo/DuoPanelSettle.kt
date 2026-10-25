package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

/** Six-frame endpoint travel. A stale task cannot mutate or clear a replacement binding. */
internal class DuoPanelSettle(from: Float, private val host: Host) : Runnable {
    interface Host {
        fun current(task: Runnable): Boolean
        fun live(): Boolean
        fun progress(value: Float)
        fun draw(value: Float)
        fun clear()
        fun arrive()
        fun post(task: Runnable)
        fun failed(error: Throwable)
    }
    private val steps = (1..6).map { step -> from + (1f - from) * step / 6f }
    private var index = 0
    private var finished = false
    override fun run() {
        if (finished || !host.current(this)) return
        if (!host.live()) { finished = true; host.clear(); return }
        val next = steps[index++]
        host.progress(next)
        val updated = runCatching { host.draw(next) }
        if (updated.isFailure) host.failed(requireNotNull(updated.exceptionOrNull()))
        if (updated.isFailure || index >= steps.size) {
            finished = true
            host.clear()
            host.progress(1f)
            host.arrive()
        } else host.post(this)
    }
}
