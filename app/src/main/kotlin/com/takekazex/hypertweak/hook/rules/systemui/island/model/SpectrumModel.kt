package com.takekazex.hypertweak.hook.rules.systemui.island.model

import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.spectrum.SmallIslandSpectrum

/** Native plugin dimensions used to build the two demand tiers. */
internal data class MusicDemandDimensions(
    val padding: Int,
    val icon: Int,
    val margin: Int,
    val cameraPadding: Int,
)

/**
 * Admission input for replacing one side summary's native cover with the native media
 * template. All gates are explicit; no host calls happen inside the policy.
 */
internal data class SpectrumAdmissionInput(
    val enabled: Boolean,
    val layout: ThreeIslandLayout,
    val extras: List<Any>,
    val primary: Any?,
    val expanded: Any?,
    val big: Any?,
    val fullFramesEmpty: Boolean,
    val mediaRendererAvailable: Boolean,
    val matches: (Any) -> Boolean,
    val mediaAreasAvailable: (Any) -> Boolean,
    val demandOf: (Any) -> MusicDemandDimensions?,
    val keyOf: (Any) -> String?,
    val seatLeft: (String?) -> Boolean?,
    val nativeSmallWidth: (Any) -> Int,
    val gap: Int,
    val clock: Int,
    val battery: Int,
    val safeLeft: Int,
    val safeRight: Int,
)

internal sealed interface SpectrumDecision {
    /** Renderer or feature disabled; nothing to say about the current cover. */
    data object Unavailable : SpectrumDecision

    /** No canonical media template among the two native small seats. */
    data object NoCandidate : SpectrumDecision

    /** Structurally impossible; keep the cover and native task seats untouched. */
    data class Rejected(val reason: String) : SpectrumDecision

    /** Admissible content, but neither demand tier fits the current side. */
    data class CoverOnly(val reason: String) : SpectrumDecision

    data class Admitted(
        val view: Any,
        val seat: SmallIslandSpectrum.Seat,
        val demand: IslandContentSize,
        val compactFallback: Boolean,
        val returnGeometry: SmallIslandSpectrum.ReturnGeometry,
    ) : SpectrumDecision
}
