package com.takekazex.hypertweak.dock

/** Coalesces animation frames before main-thread delivery; retirement discards queued work. */
internal class DockPoseMailbox {
    private var pending: DockPose? = null
    private var closed = false

    @Synchronized fun offer(pose: DockPose): Boolean {
        if (closed) return false
        val schedule = pending == null
        pending = pose
        return schedule
    }

    @Synchronized fun take(): DockPose? = pending.also { pending = null }

    @Synchronized fun close() {
        closed = true
        pending = null
    }
}
