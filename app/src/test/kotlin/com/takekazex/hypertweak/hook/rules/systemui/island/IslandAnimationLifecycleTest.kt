package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import com.takekazex.hypertweak.hook.rules.systemui.island.template.TemplateCutoutRegistry
import org.junit.Assert.*
import org.junit.Test

class IslandAnimationLifecycleTest {
    private fun runtime(host: InMemoryIslandHost) = IslandRuntime(host, object : IslandSettings {
        override val threeIslands = true
        override val landscapeFull = false
        override val landscapeMore = false
    }, { false }, null, null)

    @Test fun `vertical tracking and its rollback stay with the native animator`() {
        val host = InMemoryIslandHost()
        val owner = Any()
        host.bind(owner, IslandSlot.BIG, FakeTask("middle", owner))
        val runtime = runtime(host)
        val session = runtime.sessions.getOrCreate(owner)
        session.scene.layout = ThreeIslandLayout(471, 296, 357, 779, false, 1200)
        session.interaction.gesture.begin("left")
        assertFalse(runtime.gestures.handleSwipe(session, 2f, 60f))
        // Diagonal movement cannot change the owner of an already native gesture.
        assertFalse(runtime.gestures.handleSwipe(session, 90f, 70f))
        assertFalse(runtime.gestures.resetSwipe(session))
        assertFalse(session.interaction.gesture.allows("middle"))
        assertTrue(session.interaction.gesture.allows("left"))
    }

    @Test fun `horizontal preview rollback is consumed only when the module owns tracking`() {
        val host = InMemoryIslandHost()
        val owner = Any()
        host.bind(owner, IslandSlot.BIG, FakeTask("middle", owner))
        val runtime = runtime(host)
        val session = runtime.sessions.getOrCreate(owner)
        session.scene.layout = ThreeIslandLayout(471, 296, 357, 779, false, 1200)
        val preview = FakeTask("preview", owner)
        assertTrue(runtime.gestures.handleSwipe(session, 60f, 2f))
        session.interaction.swipePreview = preview
        assertTrue(runtime.gestures.resetSwipe(session))
        assertEquals(listOf("resetSwipe:preview"), host.calls)
        assertNull(session.interaction.swipePreview)
        assertFalse(runtime.gestures.resetSwipe(session))
    }

    @Test fun `native shadow cleanup includes lent real and fake summaries but skips the card`() {
        val host = InMemoryIslandHost()
        val owner = Any()
        val left = FakeTask("left", owner)
        val fake = FakeTask("leftFake", owner)
        val card = FakeTask("card", owner)
        host.bind(owner, IslandSlot.EXPANDED, card)
        host.fakeViews[left] = fake
        val runtime = runtime(host)
        val session = runtime.sessions.getOrCreate(owner)
        session.slot.extras = listOf(left, card)
        runtime.presentation.clearBorrowedShadows(session)
        assertEquals(listOf("call:left:clearShadow", "call:leftFake:clearShadow"), host.calls)
    }

    @Test fun `delayed cutout writes use the exact leased parent and restore on release`() {
        val registry = TemplateCutoutRegistry()
        val main = Any(); val media = Any(); val owner = Any(); val replacement = Any()
        registry.acquire(main, owner, 72)
        registry.acquire(media, owner, 0)
        assertEquals(72, registry.width(main))
        assertEquals(0, registry.width(media))
        registry.acquire(media, replacement, 0)
        registry.release(media, owner)
        assertEquals(0, registry.width(media))
        registry.acquire(media, replacement, 72) // The same parent becomes a camera template.
        assertEquals(72, registry.width(media))
        registry.release(media, replacement)
        assertNull(registry.width(media))
        assertEquals(72, registry.width(main))
        registry.release(main, owner)
        assertNull(registry.width(main))
    }
}
