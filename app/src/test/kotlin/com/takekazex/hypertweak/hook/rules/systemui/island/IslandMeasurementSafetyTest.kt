package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandStateKind
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import org.junit.Assert.*
import org.junit.Test

class IslandMeasurementSafetyTest {
    @Test fun `a real task awaiting template binding is not measured into removeByError`() {
        val host = InMemoryIslandHost()
        val owner = Any()
        val pending = FakeTask("pending", owner).also { it.state = FakeState(IslandStateKind.HIDDEN) }
        val ports = object : IslandHostPorts by host {
            override fun templateReady(view: Any) = false
        }
        val settings = object : IslandSettings {
            override val threeIslands = false
            override val landscapeFull = false
            override val landscapeMore = false
        }
        val runtime = IslandRuntime(ports, settings, { false }, null, null)
        runtime.sessions.getOrCreate(owner)
        host.queue += pending
        host.onCalculate = { pending.state = null }
        runtime.compact.refresh()
        assertEquals(FakeState(IslandStateKind.HIDDEN), pending.state)
        assertTrue(host.calls.isEmpty())
    }
    @Test fun `refresh never sends a native Empty marker through destructive template measurement`() {
        val host = InMemoryIslandHost()
        val owner = Any()
        val marker = FakeTask("Empty", owner).also { it.state = FakeState(IslandStateKind.OTHER) }
        val task = FakeTask("timer", owner).also { it.state = FakeState(IslandStateKind.HIDDEN) }
        val ports = object : IslandHostPorts by host {
            override fun key(view: Any) = if (view === marker) null else host.key(view)
        }
        val settings = object : IslandSettings {
            override val threeIslands = false
            override val landscapeFull = false
            override val landscapeMore = false
        }
        val runtime = IslandRuntime(ports, settings, { false }, null, null)
        runtime.sessions.getOrCreate(owner)
        host.queue.addAll(listOf(marker, task))
        // Actual host contract: missing left/right template calls removeByError -> state=null.
        host.onCalculate = { if (it === marker) marker.state = null }
        runtime.compact.refresh()
        assertEquals("native queue marker must retain Empty identity", FakeState(IslandStateKind.OTHER), marker.state)
        assertEquals(listOf(marker, task), host.queue)
        assertEquals(listOf("call:timer:calculateBigIslandWidth"), host.calls)
    }
}
