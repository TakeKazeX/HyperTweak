package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.host.HostRect
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MeasurementOutcome
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.spectrum.SmallIslandSpectrum
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime
import com.takekazex.hypertweak.hook.rules.systemui.island.session.TemplateAnimation
import org.junit.Assert.*
import org.junit.Test

class IslandMediaReturnTest {
    private val host = InMemoryIslandHost()
    private val owner = Any()
    private val media = FakeTask("media", owner).apply { width = 102 }
    private val timer = FakeTask("timer", owner)
    private val settings = object : IslandSettings {
        override var threeIslands = true
        override var landscapeFull = false
        override val landscapeMore = false
    }
    private var rebuilding = false
    private val ports = object : IslandHostPorts by host {
        override fun inConfigRebuild(owner: Any) = rebuilding
        override fun fixedCutoutWidth(view: Any) = 72
    }
    private val runtime = IslandRuntime(ports, settings, { false }, null, null)
    private val session = runtime.sessions.getOrCreate(owner)
    private val layout = ThreeIslandLayout(471, 349, 357, 832, false, 1200)
    private val frame = FullIslandFrame(279, IslandContentSize(90, 90), true)

    private fun remember() {
        session.spectrum.returnGeometry = SmallIslandSpectrum.ReturnGeometry(
            "media", layout, frame.content, 12, 170, 170, 0, 0, 102, seatLeft = true, screenHeight = 2600,
        )
    }

    private fun outcome(layout: ThreeIslandLayout?, extras: List<Any> = emptyList()) = MeasurementOutcome(
        layout, emptyMap(), emptyList(), extras, null, null, true, emptyList(),
    )

    @Test fun `music becoming the main island preserves the last side destination instead of rebasing it`() {
        remember()
        val remembered = session.spectrum.returnGeometry
        host.bind(owner, IslandSlot.BIG, media).bind(owner, IslandSlot.SMALL, timer)
        runtime.spectrum.afterMeasure(session, outcome(layout.copy(bigX = 353, bigWidth = 494)), host.scene, media)
        assertSame(remembered, session.spectrum.returnGeometry)
        runtime.spectrum.afterMeasure(session, outcome(null), host.scene, media)
        assertSame(remembered, session.spectrum.returnGeometry)
        assertEquals(HostRect(279, 30, 459, 132),
            runtime.spectrum.rewriteReturnRect(session, media, HostRect(832, 30, 934, 132)))
    }

    @Test fun `return position width template state and native fields agree before side admission`() {
        remember()
        host.bind(owner, IslandSlot.BIG, media).bind(owner, IslandSlot.SMALL, timer)
        session.scene.layoutIntent = true
        session.scene.layout = layout.copy(bigX = 353, bigWidth = 494)
        session.scene.portraitContent = IslandContentSize(211, 211)
        val liveLayout = session.scene.layout
        val rect = runtime.spectrum.rewriteReturnRect(session, media, HostRect(832, 30, 934, 132))!!
        assertEquals(180, rect.width)
        assertTrue(runtime.presentation.prepareNativeMedia(session, media))
        assertNull(runtime.coordinates.publishedSmallWidth(session, media))
        runtime.renderingState.render(media) {
            assertEquals(frame, runtime.templateFrame(session, media))
            assertEquals(180, runtime.coordinates.publishedSmallWidth(session, media))
            assertEquals(279, runtime.presentation.currentBigIslandGeometry(session, media, "getCurrentBigIslandX"))
            assertEquals(180, runtime.presentation.currentBigIslandGeometry(session, media, "getCurrentBigIslandWidth"))
            assertEquals(-231f, runtime.coordinates.nativeCoordinateX(session, media))
            assertTrue(runtime.fullSide(session, media))
            assertFalse(runtime.portraitMain(session, media))
            assertEquals(0, runtime.presentation.fixedCutoutWidth(session, media))
            val small = host.nextState(media, IslandSlot.SMALL)
            assertNotNull(runtime.presentation.projectedRenderState(session, media, small))
            val measured = runtime.measurement.afterMeasure(media, Any(), Any(), Any())!!
            assertEquals(rect.left, measured.x)
            assertEquals(rect.width, measured.width)
            assertEquals(90, measured.left)
            assertEquals(90, measured.right)
            assertSame(liveLayout, session.scene.layout)
            assertEquals(IslandContentSize(211, 211), session.scene.portraitContent)
            val args = Array<Any?>(9) { 0 }.apply { this[3] = 0f }
            val animation = runtime.presentation.bigIslandAnimation(media, args) as TemplateAnimation.Redirect
            assertEquals(-231f, animation.args[3]) // Centre of [279,459] relative to a 1200px screen.
            assertEquals(102, runtime.budgetSmallWidth(media))
            runtime.renderingState.native {
                assertNull(runtime.coordinates.publishedSmallWidth(session, media))
                assertFalse(runtime.fullSide(session, media))
            }
            assertEquals(180, runtime.coordinates.publishedSmallWidth(session, media))
        }
        assertFalse(runtime.fullSide(session, media))
        assertNull(runtime.coordinates.publishedSmallWidth(session, media))
    }

    @Test fun `a real side measurement revokes a rejected return plan but keeps an already issued target stable`() {
        remember()
        runtime.spectrum.rewriteReturnRect(session, media, HostRect(832, 30, 934, 132))
        val issued = session.spectrum.returnTarget
        host.bind(owner, IslandSlot.BIG, timer).bind(owner, IslandSlot.SMALL, media)
        runtime.spectrum.afterMeasure(session, outcome(layout), host.scene, timer)
        assertNull(session.spectrum.returnGeometry) // No renderer: native cover only.
        assertSame(issued, session.spectrum.returnTarget)
        runtime.renderingState.render(media) { assertEquals(frame, runtime.templateFrame(session, media)) }
        assertNull(runtime.spectrum.rewriteReturnRect(session, media, HostRect(832, 30, 934, 132)))
    }

    @Test fun `native future main content determines the return row without measuring Views`() {
        remember()
        host.bind(owner, IslandSlot.BIG, media).bind(owner, IslandSlot.SMALL, timer)
        session.spectrum.returnContents = mapOf("timer" to IslandContentSize(160, 184))
        runtime.spectrum.planReturn(session, media, timer)
        val target = runtime.spectrum.rewriteReturnRect(session, media, HostRect(832, 30, 934, 132))!!
        assertEquals(212, target.left)
        assertEquals(180, target.width)
        assertTrue(host.calls.isEmpty())
        session.spectrum.returnContents = mapOf("timer" to IslandContentSize(230, 184))
        runtime.spectrum.planReturn(session, media, timer)
        val cover = checkNotNull(session.spectrum.returnTarget)
        assertFalse(cover.frame.full)
        assertEquals(102, cover.frame.width)
        session.scene.musicSmall = media
        session.scene.fullFrames[media] = frame // Previous paired scene must not win.
        runtime.renderingState.render(media, cover) {
            assertFalse(runtime.fullSide(session, media))
            assertEquals(102, runtime.coordinates.publishedSmallWidth(session, media))
            assertEquals(cover.frame.x, runtime.coordinates.smallX(session, media))
            assertFalse(runtime.presentation.prepareNativeMedia(session, media))
        }
        val coverRect = HostRect(cover.frame.x, 30, cover.frame.x + 102, 132)
        assertEquals(coverRect, runtime.spectrum.rewriteReturnRect(session, media, coverRect))
    }

    @Test fun `an issued render snapshot survives a concurrent query but never leaks into scheduling`() {
        remember()
        host.bind(owner, IslandSlot.BIG, media)
        runtime.spectrum.rewriteReturnRect(session, media, HostRect(832, 30, 934, 132))
        val target = session.spectrum.returnTarget
        runtime.renderingState.render(media, target) {
            session.spectrum.returnTarget = null
            assertEquals(frame, runtime.templateFrame(session, media))
            runtime.renderingState.native { assertNull(runtime.spectrum.renderingReturnFrame(session, media)) }
            assertEquals(frame, runtime.templateFrame(session, media))
        }
        assertNull(runtime.spectrum.renderingReturnFrame(session, media))
        assertNull(runtime.renderingState.returnTarget())
    }

    @Test fun `landscape full renderer ignores a pending portrait cover target through rebuild and rotation`() {
        remember()
        host.bind(owner, IslandSlot.BIG, media).bind(owner, IslandSlot.SMALL, timer)
        session.spectrum.returnContents = mapOf("timer" to IslandContentSize(230, 184))
        val cover = checkNotNull(runtime.spectrum.planReturn(session, media, timer))
        assertFalse(cover.frame.full)
        assertSame(cover, runtime.spectrum.nativeReturnTarget(session, media))
        settings.landscapeFull = true
        host.orientation = com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation.LANDSCAPE
        host.bind(owner, IslandSlot.BIG, timer).bind(owner, IslandSlot.SMALL, media)
        session.scene.layoutIntent = true
        session.scene.layout = layout.copy(full = true, screenWidth = 2608)
        val full = FullIslandFrame(1365, IslandContentSize(329, 322), true)
        session.scene.fullFrames[media] = full
        assertNull(runtime.spectrum.nativeReturnTarget(session, media))
        assertTrue(runtime.presentation.prepareNativeMedia(session, media))
        runtime.renderingState.render(media, cover) {
            assertNull(runtime.spectrum.nativeReturnTarget(session, media))
            assertNull(runtime.spectrum.renderingReturnTarget(session, media))
            assertSame(full, runtime.templateFrame(session, media))
            assertTrue(runtime.fullSide(session, media))
        }
        assertNull(runtime.spectrum.rewriteReturnRect(session, media, HostRect(832, 30, 934, 132)))
        rebuilding = true
        assertFalse(runtime.landscapeFullEnabled(session))
        assertNull(runtime.spectrum.nativeReturnTarget(session, media))
        assertFalse(runtime.spectrum.hasReturnTarget(session, media))
        rebuilding = false
        settings.landscapeFull = false
        assertNull(runtime.spectrum.nativeReturnTarget(session, media)) // Compact landscape has a different row too.
        host.orientation = com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation.PORTRAIT
        assertSame(cover, runtime.spectrum.nativeReturnTarget(session, media))
    }

    @Test fun `opening the app cancels a pending return without deleting its last admitted side plan`() {
        remember()
        runtime.spectrum.rewriteReturnRect(session, media, HostRect(832, 30, 934, 132))
        runtime.spectrum.cancelReturn(session, timer)
        assertNotNull(session.spectrum.returnTarget)
        runtime.spectrum.cancelReturn(session, media)
        assertNull(session.spectrum.returnTarget)
        assertNotNull(session.spectrum.returnGeometry)
    }

    @Test fun `restoration clears return history targets and Binder content snapshots`() {
        remember()
        host.bind(owner, IslandSlot.BIG, timer).bind(owner, IslandSlot.SMALL, media)
        session.spectrum.returnContents = mapOf("timer" to IslandContentSize(93, 184))
        runtime.spectrum.rewriteReturnRect(session, media, HostRect(832, 30, 934, 132))
        runtime.settlement.restore(session)
        assertNull(session.spectrum.returnGeometry)
        assertNull(session.spectrum.returnTarget)
        assertTrue(session.spectrum.returnContents.isEmpty())
    }

    @Test fun `cutout wrong task and disabled settings cannot acquire or render a paired return target`() {
        remember()
        assertNull(runtime.spectrum.rewriteReturnRect(session, media, HostRect(564, 30, 636, 132)))
        assertNull(session.spectrum.returnTarget)
        assertNull(runtime.spectrum.rewriteReturnRect(session, timer, HostRect(832, 30, 934, 132)))
        runtime.spectrum.rewriteReturnRect(session, media, HostRect(832, 30, 934, 132))
        settings.threeIslands = false
        runtime.renderingState.render(media) {
            assertNull(runtime.spectrum.renderingReturnFrame(session, media))
            assertFalse(runtime.presentation.prepareNativeMedia(session, media))
        }
    }
}
