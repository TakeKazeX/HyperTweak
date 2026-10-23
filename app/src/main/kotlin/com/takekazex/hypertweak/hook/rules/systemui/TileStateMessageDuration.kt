package com.takekazex.hypertweak.hook.rules.systemui

/** Dwell starts at native show-animation completion; animations keep their own timing. */
object TileStateMessageDuration {
    const val DEFAULT_MS = 3000
    val SNAP_SECONDS = listOf(0f, 0.5f, 1f, 3f, 5f)
    fun normalize(value: Int): Int = ((value.coerceIn(0, 5000) + 50) / 100) * 100
    fun remaining(duration: Int, completedAt: Long, now: Long): Long =
        (normalize(duration).toLong() - (now - completedAt).coerceAtLeast(0)).coerceAtLeast(0)
}
