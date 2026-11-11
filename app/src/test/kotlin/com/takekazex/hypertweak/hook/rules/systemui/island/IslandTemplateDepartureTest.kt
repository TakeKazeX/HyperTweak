package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandStateKind
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import org.junit.Assert.*
import org.junit.Test

/** A full template leaving the row keeps its position until its own exit transition ends. */
class IslandTemplateDepartureTest {
    private val host = InMemoryIslandHost()
    private val owner = Any()
    private val main = FakeTask("main", owner)
    private val side = FakeTask("side", owner)
    private val settings = object : IslandSettings {
        override val threeIslands = false
        override val landscapeFull = false
        override val landscapeMore = false
    }
    private val releases = mutableListOf<Any>()
    private val ports = object : IslandHostPorts by host {
        override fun bigIslandView(view: Any): Any? {
            releases += view
            return null
        }
    }
    private val runtime = IslandRuntime(ports, settings, { false }, null, null)
    private val session = runtime.sessions.getOrCreate(owner)

    init {
        host.bind(owner, IslandSlot.BIG, main)
        session.presentation.fullOrigins[side] = 1200
    }

    @Test fun `hidden full template is released by its own exit transition`() {
        side.state = FakeState(IslandStateKind.HIDDEN)
        runtime.presentation.beforeStateChange(session, side)
        assertTrue(side in session.presentation.departures)
        assertTrue(releases.isEmpty())
        runtime.templates.animationEvent(session, side, "exit", "ANIM_START")
        runtime.templates.animationEvent(session, side, "older", "ANIM_CANCEL")
        assertTrue(releases.isEmpty())
        runtime.templates.animationEvent(session, side, "exit", "ANIM_FINISH")
        assertEquals(listOf<Any>(side), releases)
        assertFalse(side in session.presentation.departures)
    }

    @Test fun `promotion to the main template releases before the animation starts`() {
        host.bind(owner, IslandSlot.BIG, side)
        side.state = FakeState(IslandStateKind.BIG)
        runtime.presentation.beforeStateChange(session, side)
        assertEquals(listOf<Any>(side), releases)
        assertTrue(session.presentation.departures.isEmpty())
    }

    @Test fun `a rebind during the exit releases at once`() {
        side.state = FakeState(IslandStateKind.HIDDEN)
        runtime.presentation.beforeStateChange(session, side)
        runtime.templates.templateBound(side)
        assertEquals(listOf<Any>(side), releases)
        assertTrue(session.presentation.departures.isEmpty())
    }
}
