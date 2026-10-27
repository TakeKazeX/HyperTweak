package com.takekazex.hypertweak.hook.nativebridge

/** Admission for immediate feedback. There is no delayed work or crossing backlog. */
internal class RecentsHapticTiming {
    companion object {
        // Stock MESH_NORMAL takes up to 67 ms on the verified motor/service path.
        const val MIN_INTERVAL_NS = 70_000_000L
        const val MAX_EVENT_AGE_NS = 50_000_000L
    }

    private var lastEvent = 0L
    private var lastPlayed = 0L

    fun admit(event: Long, now: Long): Boolean {
        if (event <= 0L || event > now || event <= lastEvent) return false
        lastEvent = event
        if (now - event > MAX_EVENT_AGE_NS ||
            (lastPlayed != 0L && now - lastPlayed < MIN_INTERVAL_NS)) return false
        lastPlayed = now
        return true
    }

    fun reset(now: Long) {
        lastEvent = now
        lastPlayed = 0L
    }
}
