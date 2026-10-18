package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandStateKind
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SchedulingTransaction
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SettlementCoordinatorTest {
    private class Settings(
        override val threeIslands: Boolean = true,
        override val landscapeFull: Boolean = false,
        override val landscapeMore: Boolean = false,
    ) : IslandSettings

    private fun runtime(host: InMemoryIslandHost, settings: Settings = Settings()) =
        IslandRuntime(host, settings, { false }, null, null)

    @Test fun `returnToQueue lends the extra back before the next host event`() {
        val host = InMemoryIslandHost()
        val owner = Any()
        val big = FakeTask("big", owner)
        val small = FakeTask("small", owner)
        val extra = FakeTask("extra", owner)
        val queued = FakeTask("queued", owner)
        host.bind(owner, IslandSlot.BIG, big)
            .bind(owner, IslandSlot.SMALL, small)
            .bind(owner, IslandSlot.HIDDEN, null)
        host.queue += queued
        val runtime = runtime(host)
        val session = runtime.sessions.getOrCreate(owner)
        session.slot.extras = listOf(extra)

        runtime.settlement.returnToQueue(session)

        assertTrue(session.slot.extras.isEmpty())
        assertEquals(listOf(extra, queued), host.queue)
        assertSame(extra, host.current(owner, IslandSlot.HIDDEN))
        assertEquals(FakeState(IslandStateKind.HIDDEN), extra.state)
        assertEquals(
            listOf("lend:extra", "setCurrent:hidden=extra"),
            host.calls,
        )
    }

    @Test fun `claiming the leased small swaps our extra with the native task`() {
        val host = InMemoryIslandHost()
        val owner = Any()
        val big = FakeTask("big", owner)
        val native = FakeTask("native", owner)
        val extra = FakeTask("extra", owner)
        host.bind(owner, IslandSlot.BIG, big)
            .bind(owner, IslandSlot.SMALL, native)
            .bind(owner, IslandSlot.HIDDEN, null)
        val runtime = runtime(host)
        val session = runtime.sessions.getOrCreate(owner)
        session.slot.extras = listOf(extra)

        runtime.settlement.claimSmall(session, extra)

        assertEquals(listOf(native), session.slot.extras)
        assertSame(extra, host.current(owner, IslandSlot.SMALL))
        assertEquals(
            listOf("call:small:getCurrent", "setCurrent:small=extra", "region"),
            host.calls,
        )
    }

    @Test fun `settle leases the selected task only after a layout is published`() {
        val host = InMemoryIslandHost()
        val owner = Any()
        val big = FakeTask("big", owner).also { it.state = FakeState(IslandStateKind.BIG) }
        val small = FakeTask("small", owner).also { it.state = FakeState(IslandStateKind.SMALL) }
        val queued = FakeTask("queued", owner).also { it.state = FakeState(IslandStateKind.HIDDEN) }
        host.bind(owner, IslandSlot.BIG, big)
            .bind(owner, IslandSlot.SMALL, small)
            .bind(owner, IslandSlot.HIDDEN, null)
        host.queue += queued
        val runtime = runtime(host)
        val session = runtime.sessions.getOrCreate(owner)
        val layout = ThreeIslandLayout(500, 200, 400, 700, false, 1200)
        host.onCalculate = { view -> if (view === big) session.scene.layout = layout }
        val tx = SchedulingTransaction(session, listOf(big, small), null)

        runtime.settlement.settle(tx)

        assertTrue(tx.settled)
        assertEquals(listOf(queued), session.slot.extras)
        assertTrue(host.queue.isEmpty())
        assertSame(layout, session.scene.layout)
        assertTrue(session.scene.layoutIntent)
        assertEquals(FakeState(IslandStateKind.SMALL), queued.state)
        assertNull(host.current(owner, IslandSlot.HIDDEN))
        assertEquals(
            listOf(
                "call:small:calculateBigIslandWidth",
                "call:queued:calculateBigIslandWidth",
                "call:big:calculateBigIslandWidth",
                "call:small:calculateBigIslandWidth",
                "call:queued:calculateBigIslandWidth",
                "call:big:calculateBigIslandWidth",
                "call:small:calculateBigIslandWidth",
                "call:queued:calculateBigIslandWidth",
            ),
            host.calls.filter { it.endsWith(":calculateBigIslandWidth") },
        )
        assertEquals(
            listOf(
                "addState:hidden:queued=SMALL",
                "addState:hidden:big=null",
                "addState:hidden:small=null",
                "addState:hidden:queued=null",
            ),
            host.calls.filter { it.startsWith("addState:") },
        )
    }

    @Test fun `restore returns every lease and preserves the native origin state`() {
        val host = InMemoryIslandHost()
        val owner = Any()
        val big = FakeTask("big", owner).also { it.state = FakeState(IslandStateKind.BIG) }
        val small = FakeTask("small", owner).also { it.state = FakeState(IslandStateKind.SMALL) }
        val extra = FakeTask("extra", owner)
        val old = FakeState(IslandStateKind.SMALL)
        extra.state = old
        val queued = FakeTask("queued", owner)
        host.bind(owner, IslandSlot.BIG, big)
            .bind(owner, IslandSlot.SMALL, small)
            .bind(owner, IslandSlot.HIDDEN, null)
        host.queue += queued
        val runtime = runtime(host)
        val session = runtime.sessions.getOrCreate(owner)
        session.slot.extras = listOf(extra)
        session.scene.layout = ThreeIslandLayout(500, 200, 400, 700, false, 1200)
        session.scene.layoutIntent = true

        runtime.settlement.restore(session)

        assertTrue(session.slot.extras.isEmpty())
        assertNull(session.scene.layout)
        assertEquals(false, session.scene.layoutIntent)
        assertTrue(session.scene.fullFrames.isEmpty())
        assertEquals(listOf(extra, queued), host.queue)
        assertSame(old, extra.lastState)
        assertEquals(
            listOf(
                "lend:extra",
                "setCurrent:hidden=extra",
                "call:extra:calculateBigIslandWidth",
                "addState:hidden:extra=null",
                "call:big:calculateBigIslandWidth",
                "addState:hidden:big=null",
                "call:small:calculateBigIslandWidth",
                "addState:hidden:small=null",
                "setLastState:extra=SMALL",
                "stop:hidden",
                "region",
            ),
            host.calls,
        )
    }
}
