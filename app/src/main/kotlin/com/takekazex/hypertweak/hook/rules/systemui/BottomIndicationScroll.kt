package com.takekazex.hypertweak.hook.rules.systemui

/** Time-based ping-pong scrolling; telemetry refreshes do not restart its progress. */
internal class BottomIndicationScroll {
    private var position = 0f
    private var direction = 1
    private var pauseUntil = 0L
    private var previousFrame: Long? = null

    fun offset(now: Long, overflow: Int, pixelsPerSecond: Float): Float {
        if (overflow <= 1) {
            reset()
            return 0f
        }
        val previous = previousFrame
        if (previous == null) pauseUntil = now + END_PAUSE_MS
        val elapsed = if (previous == null) 0L else (now - previous).coerceIn(0L, 64L)
        previousFrame = now
        if (now >= pauseUntil) {
            position += direction * elapsed * pixelsPerSecond / 1000f
            if (position >= overflow || position <= 0f) {
                position = position.coerceIn(0f, overflow.toFloat())
                direction = if (position >= overflow) -1 else 1
                pauseUntil = now + END_PAUSE_MS
            }
        }
        // A shorter live value or resized viewport must never leave an empty trailing area.
        position = position.coerceIn(0f, overflow.toFloat())
        return position
    }

    fun pause() {
        previousFrame = null
    }

    fun reset() {
        position = 0f
        direction = 1
        pauseUntil = 0L
        previousFrame = null
    }

    private companion object {
        const val END_PAUSE_MS = 1_500L
    }
}
