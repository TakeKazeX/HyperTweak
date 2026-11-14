package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandStateKind
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import org.junit.Assert.*
import org.junit.Test

/** The captured device failure: two living Hidden tasks, two null-state handler shells. */
class NativeSlotRecoveryTest {
    private val host = InMemoryIslandHost()
    private val owner = Any()
    private val bigShell = Any()
    private val smallShell = Any()
    private val timer = FakeTask("timer", owner).also { it.state = FakeState(IslandStateKind.HIDDEN) }
    private val stopwatch = FakeTask("stopwatch", owner).also { it.state = FakeState(IslandStateKind.HIDDEN) }
    private val settings = object : IslandSettings {
        override var threeIslands = false
        override val landscapeFull = true
        override val landscapeMore = true
    }
    private var alpha = 0f
    private var x = -537f
    private var measurements = 0
    private val windowState = Any()
    private val ports = object : IslandHostPorts by host {
        override fun templateReady(view: Any) = true
        override fun windowState(view: Any): Any = windowState
        override fun viewModel(view: Any): Any = view
    }
    private fun publish(view: Any) {
            assertTrue("native publication must follow fresh measurement", measurements > 0)
            alpha = 1f
            x = 0f
    }
    private val runtime = IslandRuntime(ports, settings, { false }, null, null)
    private val session = runtime.sessions.getOrCreate(owner)

    init {
        host.bind(owner, IslandSlot.BIG, bigShell).bind(owner, IslandSlot.SMALL, smallShell)
        host.contents.addAll(listOf(timer, stopwatch))
        host.queue.addAll(listOf(timer, stopwatch))
        host.onCalculate = { measurements++ }
        host.onPublish = ::publish
    }

    @Test fun `live hidden tasks escape null-state slots through native refill before publication`() {
        assertFalse(runtime.enabled(session))
        assertTrue(runtime.settlement.reconcileNative(session))
        assertSame(timer, host.current(owner, IslandSlot.BIG))
        assertSame(stopwatch, host.current(owner, IslandSlot.SMALL))
        assertEquals(FakeState(IslandStateKind.BIG), timer.state)
        assertEquals(FakeState(IslandStateKind.SMALL), stopwatch.state)
        assertTrue(host.queue.isEmpty())
        assertEquals(1, host.calls.count { it == "nativeFill" })
        assertFalse(host.calls.any { it.startsWith("stop:") }) // Flush belongs to the host caller.
        assertFalse(runtime.settlement.reconcileNative(session))
    }

    @Test fun `native AppStateHandler publication inherits the event owner without a module transaction`() {
        val appHandler = Any() // Not any of BIG, SMALL, HIDDEN, EXPANDED or SHOW_ONCE.
        assertNull(runtime.sessionForPublication(appHandler))
        runtime.context.withNativeSession(session) {
            assertNull(runtime.context.transaction)
            val published = runtime.sessionForPublication(appHandler)
            assertSame(session, published)
            assertTrue(runtime.settlement.reconcileNative(checkNotNull(published)))
            assertSame(timer, host.current(owner, IslandSlot.BIG))
        }
        assertNull(runtime.sessionForPublication(appHandler))
    }

    @Test fun `disabled settings refresh repairs ownership then restores native alpha and translation`() {
        runtime.relayout.prepare(session)
        assertEquals(0f, alpha)
        assertTrue(measurements > 0)
        runtime.relayout.publish(session)
        assertEquals(1f, alpha)
        assertEquals(0f, x)
        assertSame(timer, host.current(owner, IslandSlot.BIG))
        assertFalse(runtime.schedulingEnabled(session))
    }

    @Test fun `rotation and a second toggle retain living task identities and publish both modes`() {
        runtime.relayout.prepare(session)
        runtime.relayout.publish(session)
        host.orientation = IslandOrientation.LANDSCAPE
        alpha = 0f
        runtime.relayout.prepare(session)
        runtime.relayout.publish(session)
        assertEquals(1f, alpha)
        host.orientation = IslandOrientation.PORTRAIT
        alpha = 0f
        runtime.relayout.prepare(session)
        runtime.relayout.publish(session)
        assertEquals(1f, alpha)
        assertSame(timer, host.current(owner, IslandSlot.BIG))
        assertEquals(1, host.calls.count { it == "nativeFill" })
    }

    @Test fun `configuration rebuild defers native repair until the host releases its snapshot`() {
        session.lifecycle.suspended++
        assertFalse(runtime.settlement.reconcileNative(session, refresh = true))
        assertSame(bigShell, host.current(owner, IslandSlot.BIG))
        session.lifecycle.suspended--
        assertTrue(runtime.settlement.reconcileNative(session, refresh = true))
    }

    @Test fun `repairing only SMALL preserves the valid main task`() {
        timer.state = FakeState(IslandStateKind.BIG)
        host.bind(owner, IslandSlot.BIG, timer)
        host.queue.remove(timer)
        assertTrue(runtime.settlement.reconcileNative(session))
        assertSame(timer, host.current(owner, IslandSlot.BIG))
        assertSame(stopwatch, host.current(owner, IslandSlot.SMALL))
        assertTrue("nativeFillSmall" in host.calls)
        assertFalse("nativeFill" in host.calls)
    }

    @Test fun `upgrade removes already corrupted queue entries before native refill`() {
        val damaged = Any()
        host.queue.add(0, damaged)
        host.bind(owner, IslandSlot.HIDDEN, damaged)
        assertTrue(runtime.settlement.reconcileNative(session))
        assertSame(timer, host.current(owner, IslandSlot.BIG))
        assertSame(stopwatch, host.current(owner, IslandSlot.SMALL))
        assertFalse(damaged in host.queue)
    }

    @Test fun `healthy native queue keeps its Empty ordering marker`() {
        val marker = Any()
        val emptyState = Any()
        host.bind(owner, IslandSlot.BIG, timer).bind(owner, IslandSlot.SMALL, stopwatch)
        host.queue.clear()
        host.queue += marker
        val markerPorts = object : IslandHostPorts by host {
            override fun state(view: Any) = if (view === marker) emptyState else host.state(view)
            override fun isEmptyState(value: Any?) = value === emptyState
        }
        val active = IslandRuntime(markerPorts, settings, { false }, null, null)
        assertFalse(active.settlement.reconcileNative(active.sessions.getOrCreate(owner)))
        assertEquals(listOf(marker), host.queue)
    }

    @Test fun `registered summary missing from every handler is readmitted through native arbitration`() {
        host.bind(owner, IslandSlot.BIG, null).bind(owner, IslandSlot.SMALL, null)
        host.contents.remove(stopwatch)
        host.queue.clear()
        timer.state = FakeState(IslandStateKind.BIG)
        assertTrue(runtime.settlement.reconcileNative(session, refresh = true))
        assertSame(timer, host.current(owner, IslandSlot.BIG))
        assertTrue("nativeAdmit:timer" in host.calls)
    }

    @Test fun `expanded owner is preserved but a keyless marker cannot own a published summary slot`() {
        host.bind(owner, IslandSlot.EXPANDED, timer)
        assertFalse(runtime.settlement.reconcileNative(session, refresh = true))
        assertSame(bigShell, host.current(owner, IslandSlot.BIG))
        host.bind(owner, IslandSlot.EXPANDED, null)
        val marker = FakeTask("marker", owner).also { it.state = FakeState(IslandStateKind.OTHER) }
        val markerPorts = object : IslandHostPorts by host {
            override fun key(view: Any) = if (view === marker) null else host.key(view)
        }
        host.bind(owner, IslandSlot.BIG, marker).bind(owner, IslandSlot.SMALL, null)
        val active = IslandRuntime(markerPorts, settings, { false }, null, null)
        assertTrue(active.settlement.reconcileNative(active.sessions.getOrCreate(owner), refresh = true))
        assertSame(timer, host.current(owner, IslandSlot.BIG))
        assertSame(stopwatch, host.current(owner, IslandSlot.SMALL))
    }
}
