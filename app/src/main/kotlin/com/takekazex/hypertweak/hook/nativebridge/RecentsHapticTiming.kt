package com.takekazex.hypertweak.hook.nativebridge

/**
 * Pure pacing policy shared with the JVM regression test.
 *
 * The framework keeps at most one running and one pending vibration: a request
 * arriving while a session is already queued ends the queued one with
 * CANCELLED_SUPERSEDED and takes its place
 * (`VibratorManagerService.startVibrationLocked` -> `clearNextSessionLocked`,
 * confirmed against the device's services.jar). Sending faster than the motor
 * drains therefore does not play late - it *drops* the queued crossing.
 *
 * Measured on the target (myron / OS4.0.4.0, Qualcomm HAL) with
 * `cmd vibrator_manager synced prebaked <id>` plus the service's
 * "step 1 complete" log: HEAVY_CLICK (MESH_NORMAL) 55-59 ms, TICK 59-67 ms,
 * POP 61-63 ms. A shell burst at 55 ms spacing still all finished, but 20 ms
 * spacing already logged "Dropping pending vibration ... CANCELLED_SUPERSEDED".
 * 70 ms clears the slowest measured effect with margin, so a queued crossing is
 * always the running session when it fires.
 */
internal object RecentsHapticTiming {
    /** One motor slot: spacing that never leaves a request pending. */
    const val MIN_INTERVAL_MS = 70L

    /** Maximum crossings kept queued, including the one about to be added. */
    const val MAX_PENDING = 4

    /**
     * Zero means play now; a positive value is the earliest slot at which the
     * next request is still guaranteed to be the running session.
     */
    fun delayUntilNext(lastPlayedAt: Long, now: Long): Long =
        (lastPlayedAt + MIN_INTERVAL_MS - now).coerceAtLeast(0L)

    /**
     * How many of the oldest queued crossings to discard before enqueuing one
     * more, so the backlog stays bounded and the newest crossings survive.
     */
    fun overflow(pending: Int): Int = (pending - MAX_PENDING + 1).coerceAtLeast(0)
}
