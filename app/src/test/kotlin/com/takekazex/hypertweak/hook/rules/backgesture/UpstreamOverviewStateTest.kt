package com.takekazex.hypertweak.hook.rules.backgesture

import org.junit.Assert.*
import org.junit.Test

class UpstreamOverviewStateTest {
    @Test fun `retired owners and generations cannot revive overview after exit`() {
        val state = UpstreamOverviewState()
        assertTrue(state.offer(20, 100, true))
        assertTrue(state.visible()!!)
        assertTrue(state.offer(20, 100, false))
        assertFalse(state.offer(19, 1000, true))
        assertFalse(state.offer(20, 99, true))
        assertFalse(state.visible()!!)
    }

    @Test fun `fresh owner or SystemUI generation clears prior visibility until a fresh observation`() {
        val state = UpstreamOverviewState()
        assertNull(state.visible())
        assertTrue(state.offer(20, 100, true))
        assertTrue(state.offer(21, 100, null))
        assertNull(state.visible())
        assertTrue(state.offer(21, 100, true))
        assertTrue(state.offer(21, 101, null))
        assertNull(state.visible())
        assertFalse(state.offer(0, 100, false))
        assertFalse(state.offer(21, 0, false))
    }
}
