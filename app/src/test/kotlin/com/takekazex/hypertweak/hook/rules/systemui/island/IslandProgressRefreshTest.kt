package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import com.takekazex.hypertweak.hook.rules.systemui.island.session.TemplateAnimation
import org.junit.Assert.*
import org.junit.Test

class IslandProgressRefreshTest {
    private val host = InMemoryIslandHost()
    private val owner = Any()
    private val download = FakeTask("download", owner)
    private val side = FakeTask("side", owner)
    private val settings = object : IslandSettings {
        override var threeIslands = false
        override val landscapeFull = false
        override val landscapeMore = false
        override var removePlaceholder = true
    }
    private val releases = mutableListOf<Any>()
    private val ports = object : IslandHostPorts by host {
        override fun fixedCutoutWidth(view: Any) = 72
        override fun bigIslandView(view: Any): Any? {
            // releaseFullPosition reaches the visible root only when native positioning
            // is restored. Android View animation itself is covered by device acceptance.
            releases += view
            return null
        }
    }
    private val runtime = IslandRuntime(ports, settings, { false }, null, null)
    private val session = runtime.sessions.getOrCreate(owner)
    private val content = IslandContentSize(93, 216)

    init {
        host.bind(owner, IslandSlot.BIG, download).bind(owner, IslandSlot.SMALL, side)
        session.scene.layoutIntent = true
        session.scene.layout = ThreeIslandLayout(471, 381, 359, 864, false, 1200)
        session.scene.portraitContent = content
        session.scene.contentSizes[download] = content
    }

    @Test fun `repeated progress binding retains published geometry until new measurement`() {
        repeat(50) {
            runtime.templates.templateBound(download)
            assertFalse(session.health.failed)
            assertTrue(runtime.portraitMain(session, download))
            assertEquals(content, session.scene.portraitContent)
            assertTrue(releases.isEmpty())
            // Clearing the measurement cache must not change presentation ownership.
            assertFalse(session.scene.contentSizes.containsKey(download))
            val args = arrayOfNulls<Any>(11).apply { this[3] = 0f }
            val animation = runtime.presentation.bigIslandAnimation(download, args) as TemplateAnimation.Redirect
            assertEquals(61.5f, animation.args[3])
            host.sizes[download] = content
            val measured = runtime.measurement.afterMeasure(download, Any(), Any(), Any())!!
            assertEquals(471, measured.x)
            assertEquals(381, measured.width)
        }
    }

    @Test fun `new progress text width updates geometry without restoring native origin`() {
        runtime.templates.templateBound(download)
        host.sizes[download] = IslandContentSize(103, 226)
        val measured = runtime.measurement.afterMeasure(download, Any(), Any(), Any())!!
        assertEquals(461, measured.x)
        assertEquals(401, measured.width)
        assertEquals(IslandContentSize(103, 226), session.scene.portraitContent)
        assertTrue(releases.isEmpty())
    }

    @Test fun `side real and fake binding cannot invalidate main compact geometry`() {
        runtime.templates.templateBound(side)
        runtime.templates.templateBound(side, Any())
        assertEquals(content, session.scene.portraitContent)
        runtime.templates.releaseFullPosition(session, download)
        assertFalse(download in releases)
        assertTrue(runtime.portraitMain(session, download))
    }

    @Test fun `configuration binding invalidates old geometry for deferred publication`() {
        session.template.configurationBindings.expect(download)
        runtime.templates.templateBound(download)
        assertFalse(session.health.failed)
        assertNull(session.scene.portraitContent)
        assertTrue(download in releases)
        assertFalse(session.template.configurationBindings.bound(download))
    }

    @Test fun `disabling compact layout restores native position on next binding`() {
        settings.removePlaceholder = false
        runtime.templates.templateBound(download)
        assertFalse(session.health.failed)
        assertNull(session.scene.portraitContent)
        assertTrue(download in releases)
    }

    @Test fun `expanded main binding restores native position`() {
        host.bind(owner, IslandSlot.EXPANDED, download)
        runtime.templates.templateBound(download)
        assertFalse(session.health.failed)
        assertNull(session.scene.portraitContent)
        assertTrue(download in releases)
    }
}
