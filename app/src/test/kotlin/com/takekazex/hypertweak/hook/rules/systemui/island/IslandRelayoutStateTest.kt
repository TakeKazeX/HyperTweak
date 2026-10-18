package com.takekazex.hypertweak.hook.rules.systemui.island
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandRelayoutState

import org.junit.Assert.*
import org.junit.Test

class IslandRelayoutStateTest {
    @Test fun `late fake template invalidates prepared real template generation`() {
        val state = IslandRelayoutState()
        state.request()
        val realTemplate = state.generation
        state.prepared(realTemplate)
        assertEquals(IslandRelayoutState.Phase.PUBLISH, state.phase)
        state.request() // The native fake template finishes after the real template.
        state.published(realTemplate)
        assertEquals(IslandRelayoutState.Phase.PREPARE, state.phase)
        val complete = state.generation
        state.prepared(complete)
        state.published(complete)
        assertEquals(IslandRelayoutState.Phase.IDLE, state.phase)
    }

    @Test fun `new rotation during prepare cannot publish stale geometry`() {
        val state = IslandRelayoutState()
        state.request()
        val portrait = state.generation
        state.request()
        state.prepared(portrait)
        assertEquals(IslandRelayoutState.Phase.PREPARE, state.phase)
        state.prepared(state.generation)
        assertEquals(IslandRelayoutState.Phase.PUBLISH, state.phase)
    }

    @Test fun `retired generation ignores both delayed preparation and publication`() {
        val state = IslandRelayoutState()
        state.request()
        val old = state.generation
        state.cancel()
        state.prepared(old)
        state.published(old)
        assertEquals(IslandRelayoutState.Phase.IDLE, state.phase)
    }
}
