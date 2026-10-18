package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.policy.SummarySwipePolicy
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import org.junit.Assert.assertEquals
import org.junit.Test

class SummarySwipePolicyTest {
    private val frames = listOf(
        FullIslandFrame(100, IslandContentSize(50, 50)),
        FullIslandFrame(210, IslandContentSize(50, 50)),
    )

    @Test fun `More disabled always keeps the fixed three-seat capacity`() {
        assertEquals(3, SummarySwipePolicy.capacity(
            moreEnabled = false, hasNext = true, nextWidth = 100,
            frames = frames, rowGap = 10, rowAvailable = 500, visibleCount = 3,
        ))
    }

    @Test fun `native two-slot layout also keeps three seats`() {
        assertEquals(3, SummarySwipePolicy.capacity(
            moreEnabled = true, hasNext = true, nextWidth = 100,
            frames = emptyList(), rowGap = 10, rowAvailable = 500, visibleCount = 2,
        ))
    }

    @Test fun `a queued summary adds one seat only when its natural width fits`() {
        assertEquals(3, SummarySwipePolicy.capacity(
            moreEnabled = true, hasNext = true, nextWidth = 100,
            frames = frames, rowGap = 10, rowAvailable = 500, visibleCount = 2,
        ))
        assertEquals(2, SummarySwipePolicy.capacity(
            moreEnabled = true, hasNext = true, nextWidth = 400,
            frames = frames, rowGap = 10, rowAvailable = 500, visibleCount = 2,
        ))
        assertEquals(2, SummarySwipePolicy.capacity(
            moreEnabled = true, hasNext = true, nextWidth = null,
            frames = frames, rowGap = 10, rowAvailable = 500, visibleCount = 2,
        ))
    }

    @Test fun `no queued summary retains the visible seats with a two-seat floor`() {
        assertEquals(2, SummarySwipePolicy.capacity(
            moreEnabled = true, hasNext = false, nextWidth = null,
            frames = frames, rowGap = 10, rowAvailable = 500, visibleCount = 1,
        ))
        assertEquals(3, SummarySwipePolicy.capacity(
            moreEnabled = true, hasNext = false, nextWidth = null,
            frames = frames, rowGap = 10, rowAvailable = 500, visibleCount = 3,
        ))
    }
}
