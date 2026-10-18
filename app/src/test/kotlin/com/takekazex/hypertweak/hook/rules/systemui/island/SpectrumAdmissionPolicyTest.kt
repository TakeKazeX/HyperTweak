package com.takekazex.hypertweak.hook.rules.systemui.island

import com.takekazex.hypertweak.hook.rules.systemui.island.model.MusicDemandDimensions
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SpectrumAdmissionInput
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SpectrumDecision
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.spectrum.SpectrumAdmissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumAdmissionPolicyTest {
    private val music = Any()
    private val extra = Any()
    private val big = Any()
    private val layout = ThreeIslandLayout(320, 430, 250, 770, false, 1200)
    private val demand = MusicDemandDimensions(padding = 25, icon = 62, margin = 3, cameraPadding = 3)
    private val widths = mapOf<Any, Int>(music to 50, extra to 60)
    private val keys = mapOf<Any, String>(music to "media", extra to "timer")

    private fun input(
        layout: ThreeIslandLayout = this.layout,
        extras: List<Any> = emptyList(),
        primary: Any? = music,
        expanded: Any? = null,
        fullFramesEmpty: Boolean = true,
        enabled: Boolean = true,
        mediaRendererAvailable: Boolean = true,
        matches: (Any) -> Boolean = { it === music },
        mediaAreasAvailable: (Any) -> Boolean = { true },
        demand: MusicDemandDimensions? = this.demand,
        keyOf: (Any) -> String? = keys::get,
        seatLeft: (String?) -> Boolean? = { true },
        widthOf: (Any) -> Int = { widths[it] ?: 0 },
        otherWidth: Int = 0,
    ) = SpectrumAdmissionInput(
        enabled = enabled,
        layout = layout,
        extras = extras,
        primary = primary,
        expanded = expanded,
        big = big,
        fullFramesEmpty = fullFramesEmpty,
        mediaRendererAvailable = mediaRendererAvailable,
        matches = matches,
        mediaAreasAvailable = mediaAreasAvailable,
        demandOf = { demand },
        keyOf = keyOf,
        seatLeft = seatLeft,
        nativeSmallWidth = { view -> if (view === extra && otherWidth > 0) otherWidth else widthOf(view) },
        gap = 20,
        clock = 100,
        battery = 100,
        safeLeft = 0,
        safeRight = 0,
    )

    @Test fun `paired demand is admitted on the current left seat without moving it`() {
        val decision = SpectrumAdmissionPolicy.evaluate(input()) as SpectrumDecision.Admitted
        assertEquals(music, decision.view)
        assertTrue(decision.seat.left)
        assertEquals(186, decision.demand.width)
        assertEquals(114, decision.seat.frame.x)
        assertEquals(186, decision.seat.frame.width)
        assertTrue(!decision.compactFallback)
        assertEquals(186, decision.returnGeometry.content.width)
    }

    @Test fun `right seat keeps its native side and admits the paired demand`() {
        val decision = SpectrumAdmissionPolicy.evaluate(input(seatLeft = { false })) as SpectrumDecision.Admitted
        assertTrue(!decision.seat.left)
        assertEquals(770, decision.seat.frame.x)
        assertEquals(186, decision.seat.frame.width)
    }

    @Test fun `compact fallback removes only the camera-facing pad`() {
        val decision = SpectrumAdmissionPolicy.evaluate(input(
            layout = layout.copy(bigX = 300),
        )) as SpectrumDecision.Admitted
        assertTrue(decision.compactFallback)
        assertEquals(180, decision.demand.width)
        assertEquals(100, decision.seat.frame.x)
        assertEquals(180, decision.returnGeometry.content.width)
    }

    @Test fun `both demand tiers failing retains the native cover and side seats`() {
        val decision = SpectrumAdmissionPolicy.evaluate(input(layout = layout.copy(bigX = 200)))
        assertTrue(decision is SpectrumDecision.CoverOnly)
        assertTrue((decision as SpectrumDecision.CoverOnly).reason.contains("do not fit"))
    }

    @Test fun `the other occupied seat must still fit on the opposite side`() {
        val decision = SpectrumAdmissionPolicy.evaluate(input(
            extras = listOf(extra),
            otherWidth = 1000,
        ))
        assertTrue(decision is SpectrumDecision.CoverOnly)
    }

    @Test fun `a different island can be the media candidate on the extra seat`() {
        val decision = SpectrumAdmissionPolicy.evaluate(input(
            extras = listOf(music),
            primary = extra,
            matches = { it === music },
            seatLeft = { false },
        )) as SpectrumDecision.Admitted
        assertEquals(music, decision.view)
        assertTrue(!decision.seat.left)
    }

    @Test fun `ambiguous media templates are rejected instead of choosing one`() {
        val decision = SpectrumAdmissionPolicy.evaluate(input(
            extras = listOf(extra),
            matches = { it === music || it === extra },
        ))
        assertTrue(decision is SpectrumDecision.Rejected)
        assertTrue((decision as SpectrumDecision.Rejected).reason.contains("ambiguous"))
    }

    @Test fun `expanded and big views never become side media`() {
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(expanded = music)) is SpectrumDecision.NoCandidate)
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(primary = big, matches = { true })) is SpectrumDecision.NoCandidate)
    }

    @Test fun `structural gates keep the cover without touching scheduler state`() {
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(enabled = false)) is SpectrumDecision.Unavailable)
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(mediaRendererAvailable = false)) is SpectrumDecision.Unavailable)
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(layout = layout.copy(full = true))) is SpectrumDecision.Rejected)
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(extras = listOf(extra, Any()))) is SpectrumDecision.Rejected)
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(fullFramesEmpty = false)) is SpectrumDecision.Rejected)
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(primary = null)) is SpectrumDecision.Rejected)
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(mediaAreasAvailable = { false })) is SpectrumDecision.Rejected)
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(demand = null)) is SpectrumDecision.Rejected)
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(demand = demand.copy(padding = -1))) is SpectrumDecision.Rejected)
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(keyOf = { null })) is SpectrumDecision.Rejected)
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(seatLeft = { null })) is SpectrumDecision.Rejected)
        assertTrue(SpectrumAdmissionPolicy.evaluate(input(matches = { false })) is SpectrumDecision.NoCandidate)
    }

    @Test fun `return geometry follows the admitted demand and rebases after rotation`() {
        val decision = SpectrumAdmissionPolicy.evaluate(input()) as SpectrumDecision.Admitted
        // Native app-close always proposes its right slot; scheduling owns the remembered left seat.
        val target = decision.returnGeometry.target(830, 93)
        assertEquals(114, target!!.x)
        assertEquals(186, target.width)
        val rebased = SpectrumAdmissionPolicy.rebase(decision.returnGeometry,
            layout.copy(bigX = 500), 8, 90, 80, 1, 2)
        assertEquals(500, rebased!!.layout.bigX)
        assertEquals(8, rebased.gap)
        assertEquals(90, rebased.clock)
        assertEquals(2, rebased.safeRight)
        assertEquals(186, rebased.content.width)
        assertEquals(null, SpectrumAdmissionPolicy.rebase(null, layout, 8, 90, 80, 0, 0))
    }
}
