package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandStateKind
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import org.junit.Assert.*
import org.junit.Test

class IslandNativePortraitSchedulingTest {
    private val host = InMemoryIslandHost()
    private val owner = Any()
    private val big = FakeTask("timer", owner)
    private val small = FakeTask("stopwatch", owner)
    private val settings = object : IslandSettings {
        override var threeIslands = false
        override var landscapeFull = true
        override var landscapeMore = true
        override val removePlaceholder = true
    }
    private val runtime = IslandRuntime(host, settings, { false }, null, null)
    private val session = runtime.sessions.getOrCreate(owner)

    @Test fun landscapeOptionsDoNotTakeOverNativePortraitScheduling() {
        assertFalse(runtime.enabled(session))
        assertFalse(runtime.portraitCompactEnabled(session))
        assertFalse(runtime.schedulingEnabled(session))
        host.orientation = IslandOrientation.LANDSCAPE
        assertTrue(runtime.schedulingEnabled(session))
        host.orientation = IslandOrientation.PORTRAIT
        settings.threeIslands = true
        assertTrue(runtime.schedulingEnabled(session))
    }

    @Test fun settingsRefreshMeasuresBothNativeSummariesWithoutStoppingOrChangingHandlers() {
        host.bind(owner, IslandSlot.BIG, big).bind(owner, IslandSlot.SMALL, small)
        runtime.relayout.prepare(session)
        assertEquals(listOf("call:timer:calculateBigIslandWidth", "call:stopwatch:calculateBigIslandWidth"), host.calls)
        assertSame(big, host.current(owner, IslandSlot.BIG))
        assertSame(small, host.current(owner, IslandSlot.SMALL))
    }

    @Test fun anExpandedMainCanKeepItsNativeSlotEmptyUntilTheHostPublishesCollapse() {
        host.bind(owner, IslandSlot.EXPANDED, big).bind(owner, IslandSlot.SMALL, small)
        runtime.relayout.prepare(session)
        assertEquals(listOf("call:stopwatch:calculateBigIslandWidth"), host.calls)
        assertNull(host.current(owner, IslandSlot.BIG))
        assertSame(big, host.current(owner, IslandSlot.EXPANDED))
        assertSame(small, host.current(owner, IslandSlot.SMALL))
    }

    @Test fun disablingThreeIslandsDropsOldCoordinatesBeforeNativeCollapse() {
        settings.threeIslands = true
        host.bind(owner, IslandSlot.BIG, big).bind(owner, IslandSlot.SMALL, small)
        session.scene.layout = ThreeIslandLayout(471, 349, 357, 832, false, 1200)
        session.scene.layoutIntent = true
        session.interaction.departingPositions[small] = 832
        session.presentation.fullPresentation[small] = true
        assertNotNull(runtime.coordinates.nativeSmallTargetX(session))
        settings.threeIslands = false
        runtime.relayout.prepare(session)
        assertNull(session.scene.layout)
        assertFalse(session.scene.layoutIntent)
        assertTrue(session.interaction.departingPositions.isEmpty())
        assertTrue(session.presentation.fullPresentation.isEmpty())
        assertEquals(listOf("call:timer:calculateBigIslandWidth", "call:stopwatch:calculateBigIslandWidth"), host.calls)
        assertNull(runtime.coordinates.nativeSmallTargetX(session))
    }

    @Test fun disablingRevokesEveryProjectionBeforeTheDeferredRelayout() {
        settings.threeIslands = true
        host.bind(owner, IslandSlot.BIG, big).bind(owner, IslandSlot.SMALL, small)
        session.scene.layout = ThreeIslandLayout(471, 349, 357, 832, false, 1200)
        session.scene.fullFrames[small] = FullIslandFrame(832, IslandContentSize(102, 80), true)
        session.scene.musicSmall = small
        session.presentation.fullPresentation[small] = true
        session.interaction.departingPositions[big] = 357
        val state = FakeState(IslandStateKind.SMALL)
        assertNotNull(runtime.coordinates.nativeCoordinateX(session, small))
        settings.threeIslands = false
        assertNull(runtime.coordinates.nativeCoordinateX(session, small))
        assertNull(runtime.coordinates.smallX(session, big))
        assertNull(runtime.coordinates.nativeSmallTargetX(session))
        assertNull(runtime.coordinates.publishedSmallWidth(session, small))
        assertNull(runtime.presentation.currentBigIslandGeometry(session, small, "getCurrentBigIslandX"))
        assertEquals(state to state, runtime.presentation.resolveState(session, small, state, state))
        assertFalse(runtime.gestures.shouldBlockCollapse(session, "swipe"))
        assertFalse(runtime.gestures.shouldCaptureSwipe(session))
        assertTrue(host.calls.none { it.startsWith("set") || it.startsWith("addState:") || it.startsWith("stop:") })
    }

    @Test fun nativePublishRestoresDisplayStateAfterTheMeasuredFrame() {
        val windowState = Any()
        val ports = object : com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts by host {
            override fun windowState(view: Any) = windowState
            override fun viewModel(view: Any) = Any()
        }
        val active = IslandRuntime(ports, settings, { false }, null, null)
        val current = active.sessions.getOrCreate(owner)
        host.bind(owner, IslandSlot.BIG, big).bind(owner, IslandSlot.SMALL, small)
        active.relayout.publish(current)
        assertEquals(listOf("configChange=true", "addState:hidden:timer=null", "setLastState:timer=null", "addState:hidden:stopwatch=null", "setLastState:stopwatch=null", "stop:hidden", "configChange=false", "region"), host.calls)
    }

    @Test fun passiveRestoreAndRetirementNeverFlushTheNativeHandlerList() {
        host.bind(owner, IslandSlot.BIG, big).bind(owner, IslandSlot.SMALL, small)
        session.interaction.departingPositions[small] = 832
        session.presentation.fullPresentation[small] = true
        session.presentation.fullOrigins[small] = 1200
        repeat(3) { runtime.settlement.restore(session) }
        assertTrue(session.presentation.fullOrigins.isEmpty())
        assertTrue(session.presentation.fullPresentation.isEmpty())
        assertTrue(session.interaction.departingPositions.isEmpty())
        assertTrue(host.calls.isEmpty())
        assertSame(big, host.current(owner, IslandSlot.BIG))
        assertSame(small, host.current(owner, IslandSlot.SMALL))
    }

    @Test fun disablingWithABorrowedTaskReturnsItOnceThenLeavesHandlersNative() {
        val extra = FakeTask("extra", owner).also { it.state = FakeState(IslandStateKind.SMALL) }
        host.bind(owner, IslandSlot.BIG, big).bind(owner, IslandSlot.SMALL, small)
        session.slot.extras = listOf(extra)
        runtime.relayout.prepare(session)
        assertEquals(listOf(extra), host.queue)
        assertEquals(FakeState(IslandStateKind.HIDDEN), extra.state)
        assertTrue(session.slot.extras.isEmpty())
        assertEquals(1, host.calls.count { it.startsWith("stop:") })
        host.calls.clear()
        runtime.settlement.restore(session)
        runtime.relayout.publish(session)
        assertEquals(listOf("region"), host.calls)
        assertSame(big, host.current(owner, IslandSlot.BIG))
        assertSame(small, host.current(owner, IslandSlot.SMALL))
    }
}
