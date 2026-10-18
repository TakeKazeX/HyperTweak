package com.takekazex.hypertweak.hook.rules.systemui.island.policy

import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame

/** Dynamic carousel capacity, independent of Android views and preference reads. */
internal object SummarySwipePolicy {
    /**
     * Three seats are always available without More. With More, one extra queued summary is
     * admitted only when the next natural width still fits the measured row.
     */
    fun capacity(
        moreEnabled: Boolean,
        hasNext: Boolean,
        nextWidth: Int?,
        frames: Collection<FullIslandFrame>,
        rowGap: Int,
        rowAvailable: Int,
        visibleCount: Int,
    ): Int {
        if (!moreEnabled || frames.isEmpty()) return 3
        if (!hasNext) return visibleCount.coerceAtLeast(2)
        val used = frames.sumOf { it.width.toLong() } + rowGap.toLong() * frames.size
        return if (nextWidth != null && nextWidth > 0 && used + nextWidth <= rowAvailable) {
            visibleCount + 1
        } else {
            visibleCount.coerceAtLeast(2)
        }
    }
}
