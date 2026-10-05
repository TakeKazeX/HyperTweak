package com.takekazex.hypertweak.hook.rules.system

/** The desired vendor state, independent of delayed/stale state observations. */
internal object BatteryAutoPowerSavePolicy {
    const val MIN_THRESHOLD = 10
    const val MAX_THRESHOLD = 50
    const val DEFAULT_THRESHOLD = 20

    fun target(
        enabled: Boolean,
        threshold: Int,
        charging: Boolean,
        exitWhenCharging: Boolean,
        level: Int,
        justPlugged: Boolean = false,
        justUnplugged: Boolean = false,
    ): Boolean? {
        if (level !in 0..100) return null
        val bounded = threshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
        if (charging) return if (exitWhenCharging && justPlugged) false else null
        if (exitWhenCharging && justUnplugged) return level <= bounded
        if (enabled && level <= bounded) return true
        return null
    }
}

/** Deduplicate battery/connection broadcasts while preserving a fast unplug/replug edge. */
internal class ChargerConnectionTracker {
    private var previous: Boolean? = null
    var justDisconnected: Boolean = false
        private set

    fun update(connected: Boolean, disconnectedEvent: Boolean = false): Boolean {
        justDisconnected = previous == true && !connected
        if (disconnectedEvent) {
            // The next sticky may already describe a later replug. Remember the trusted event
            // without mistaking this queued disconnect callback for the new connection itself.
            previous = false
            return false
        }
        val edge = previous == false && connected
        previous = connected
        return edge
    }
}
