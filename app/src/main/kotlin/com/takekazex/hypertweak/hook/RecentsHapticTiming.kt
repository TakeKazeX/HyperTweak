package com.takekazex.hypertweak.hook

/** Pure timing policy shared with the JVM regression test. */
internal object RecentsHapticTiming {
    /**
     * Xiaomi's MESH_NORMAL maps to HEAVY_CLICK and measures 48 ms in the target
     * vendor HAL. 55 ms leaves room for the service callback while keeping the
     * predefined effect intact (about 18 ticks per second).
     */
    const val MIN_INTERVAL_MS = 55L

    /** Zero means play now; a positive value is the next free coalescing slot. */
    fun delayUntilNext(lastPlayedAt: Long, now: Long): Long =
        (lastPlayedAt + MIN_INTERVAL_MS - now).coerceAtLeast(0L)
}
