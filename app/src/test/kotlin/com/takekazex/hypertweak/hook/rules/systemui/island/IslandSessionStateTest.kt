package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandStateKind
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandSessionState
import org.junit.Assert.*
import org.junit.Test

class IslandSessionStateTest {
    private val host = InMemoryIslandHost()
    private var retiring = false
    private val settings = object : IslandSettings {
        override var threeIslands = true
        override val landscapeFull = false
        override val landscapeMore = false
        override val removePlaceholder = false
    }
    private val state: IslandSessionState = IslandRuntime(host, settings, { retiring }, null, null)
    private val owner = Any()
    private val session = state.sessions.getOrCreate(owner)
    private val small = FakeTask("small", owner)

    @Test fun contentLookupNeverCreatesAnUnregisteredSession() {
        assertSame(session, state.sessionForView(small))
        assertNull(state.sessionForView(Any()))
        assertNull(state.sessionForView(FakeTask("foreign", Any())))
        state.sessions.remove(owner)
        assertNull(state.sessionForView(small))
        assertTrue(state.sessions.snapshot().isEmpty())
        assertNotSame(session, state.sessions.getOrCreate(owner))
    }

    @Test fun retiringAndSuspendedSessionsStopPolicyProjectionImmediately() {
        assertTrue(state.enabled(session))
        session.lifecycle.suspended = 1
        assertFalse(state.enabled(session))
        session.lifecycle.suspended = 0
        retiring = true
        assertFalse(state.enabled(session))
        retiring = false
        settings.threeIslands = false
        assertFalse(state.enabled(session))
    }

    @Test fun summariesDeduplicatePrimaryAndBorrowedSlotsWhileQueueKeepsOnlyHidden() {
        val extra = FakeTask("extra", owner)
        host.bind(owner, IslandSlot.BIG, small).bind(owner, IslandSlot.SMALL, small)
        session.slot.extras = listOf(small, extra)
        assertEquals(listOf(small, extra), state.summaries(session))
        small.state = FakeState(IslandStateKind.SMALL)
        extra.state = FakeState(IslandStateKind.HIDDEN)
        host.queue.addAll(listOf(small, extra))
        assertEquals(listOf(extra), state.queuedSummaries(session))
    }

    @Test fun departingViewKeepsItsPositionUntilReadmittedToSmallSlot() {
        session.interaction.departingPositions[small] = 123
        assertEquals(123, state.smallX(session, small))
        host.bind(owner, IslandSlot.SMALL, small)
        assertNull(state.smallX(session, small)) // Never borrow a departed frame before measurement.
        session.scene.layout = ThreeIslandLayout(471, 349, 357, 832, false, 1200)
        assertEquals(832, state.smallX(session, small))
        session.scene.fullFrames[small] = FullIslandFrame(99, IslandContentSize(40, 50), true)
        assertEquals(99, state.smallX(session, small))
    }

    @Test fun disabledFeatureKeepsNativeWidthDespiteCachedFullTemplate() {
        small.width = 102
        host.bind(owner, IslandSlot.SMALL, small)
        session.scene.layout = ThreeIslandLayout(471, 349, 357, 832, false, 1200)
        session.scene.fullFrames[small] = FullIslandFrame(99, IslandContentSize(40, 50), true)
        session.scene.musicSmall = small
        assertTrue(state.fullTemplate(session, small))
        assertEquals(90, state.sideWidth(session, small))
        settings.threeIslands = false
        assertFalse(state.fullTemplate(session, small))
        assertEquals(102, state.sideWidth(session, small))
    }
}
