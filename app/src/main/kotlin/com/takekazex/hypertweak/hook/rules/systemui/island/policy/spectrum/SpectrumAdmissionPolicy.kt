package com.takekazex.hypertweak.hook.rules.systemui.island.policy.spectrum

import com.takekazex.hypertweak.hook.rules.systemui.island.model.SpectrumAdmissionInput
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SpectrumDecision
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout

/**
 * One canonical media template may replace exactly one side summary. The current seat is
 * never moved and both sides must keep their occupied/gap constraints. A full paired demand
 * is preferred; the compact tier is a deliberate fallback for the camera-facing pad that the
 * side-media lease removes.
 */
internal object SpectrumAdmissionPolicy {
    fun evaluate(input: SpectrumAdmissionInput): SpectrumDecision {
        if (!input.enabled || !input.mediaRendererAvailable) return SpectrumDecision.Unavailable
        if (input.layout.full || input.extras.size > 1 || !input.fullFramesEmpty) {
            return SpectrumDecision.Rejected("layout already owns full seats")
        }
        val primary = input.primary ?: return SpectrumDecision.Rejected("native small slot unavailable")
        val candidates = listOfNotNull(primary, input.extras.singleOrNull())
            .filter { it !== input.expanded && it !== input.big && input.matches(it) }
        if (candidates.isEmpty()) return SpectrumDecision.NoCandidate
        if (candidates.size > 1) return SpectrumDecision.Rejected("ambiguous media template")
        val music = candidates.single()
        val other = if (music === primary) input.extras.singleOrNull() else primary
        if (!input.mediaAreasAvailable(music)) {
            return SpectrumDecision.Rejected("native media areas unavailable")
        }
        val dimensions = input.demandOf(music)
            ?: return SpectrumDecision.Rejected("native music dimensions unavailable")
        val paired = SmallIslandSpectrum.pairedDemand(
            dimensions.padding, dimensions.icon, dimensions.margin,
        ) ?: return SpectrumDecision.Rejected("invalid paired demand")
        val compact = SmallIslandSpectrum.compactDemand(
            dimensions.padding, dimensions.icon, dimensions.margin, dimensions.cameraPadding,
        )
        val key = input.keyOf(music) ?: return SpectrumDecision.Rejected("missing island key")
        val currentLeft = input.seatLeft(key) ?: return SpectrumDecision.Rejected("current seat unknown")
        val otherWidth = other?.let(input.nativeSmallWidth) ?: 0

        fun geometry(size: IslandContentSize) = SmallIslandSpectrum.ReturnGeometry(
            key, input.layout, size, input.gap, input.clock, input.battery,
            input.safeLeft, input.safeRight, otherWidth, currentLeft,
        )

        fun seat(size: IslandContentSize): SmallIslandSpectrum.Seat? =
            SmallIslandSpectrum.choose(
                input.layout, size, input.gap, input.clock, input.battery,
                input.safeLeft, input.safeRight, currentLeft, otherWidth,
            )

        seat(paired)?.let { admitted ->
            return SpectrumDecision.Admitted(music, admitted, paired, compactFallback = false, geometry(paired))
        }
        if (compact != null) {
            seat(compact)?.let { admitted ->
                return SpectrumDecision.Admitted(music, admitted, compact, compactFallback = true, geometry(compact))
            }
        }
        return SpectrumDecision.CoverOnly("paired and compact demand do not fit")
    }

    /** Refresh the stored return geometry after every base layout, even when admission fails. */
    fun rebase(
        previous: SmallIslandSpectrum.ReturnGeometry?,
        layout: ThreeIslandLayout,
        gap: Int,
        clock: Int,
        battery: Int,
        safeLeft: Int,
        safeRight: Int,
    ): SmallIslandSpectrum.ReturnGeometry? = previous?.copy(
        layout = layout, gap = gap, clock = clock, battery = battery,
        safeLeft = safeLeft, safeRight = safeRight,
    )
}
