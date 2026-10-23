package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.assertEquals
import org.junit.Test

class TileStateMessageDurationTest {
    @Test fun `live changes use elapsed dwell instead of restarting the full timer`() {
        assertEquals(3800L, TileStateMessageDuration.remaining(5000, 1000, 2200))
        assertEquals(0L, TileStateMessageDuration.remaining(500, 1000, 2200))
        assertEquals(1800L, TileStateMessageDuration.remaining(3000, 1000, 2200))
        assertEquals(0L, TileStateMessageDuration.remaining(0, 1000, 1000))
    }

    @Test fun `restored settings and expired timers stay within bounds`() {
        assertEquals(1200, TileStateMessageDuration.normalize(1249))
        assertEquals(1300, TileStateMessageDuration.normalize(1250))
        assertEquals(0L, TileStateMessageDuration.remaining(-1, 1000, 1000))
        assertEquals(5000L, TileStateMessageDuration.remaining(Int.MAX_VALUE, 1000, 1000))
        assertEquals(0L, TileStateMessageDuration.remaining(5000, 1000, 10000))
    }
}
