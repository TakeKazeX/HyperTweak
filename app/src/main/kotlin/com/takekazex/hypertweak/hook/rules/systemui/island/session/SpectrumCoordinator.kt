package com.takekazex.hypertweak.hook.rules.systemui.island.session

import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.island.host.HostRect
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandScene
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MeasurementOutcome
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SpectrumAdmissionInput
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SpectrumDecision
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.spectrum.SpectrumAdmissionPolicy
import com.takekazex.hypertweak.hook.rules.systemui.island.spectrum.IslandLyricGuard
import com.takekazex.hypertweak.util.DebugLog

/**
 * Owns side-media admission and the lyric guard. Every failure here is soft: the native cover
 * and the scheduler's real task seats must remain untouched.
 */
internal class SpectrumCoordinator(private val runtime: IslandRuntime) {
    private val host get() = runtime.host

    fun afterMeasure(
        session: IslandSession,
        outcome: MeasurementOutcome,
        scene: IslandScene,
        big: Any?,
    ): MeasurementOutcome {
        val layout = outcome.layout
        if (layout == null) {
            releaseLyricGuard(session)
            return outcome
        }
        session.spectrum.returnGeometry = SpectrumAdmissionPolicy.rebase(
            session.spectrum.returnGeometry, layout, scene.gap, scene.clockWidth, scene.batteryWidth,
            scene.safe.left, scene.safe.right,
        )
        val primary = host.current(session.owner, IslandSlot.SMALL)
        val expanded = host.current(session.owner, IslandSlot.EXPANDED)
        val matcher = runtime.musicSpectrum
        val input = SpectrumAdmissionInput(
            enabled = runtime.preferences.threeIslands,
            layout = layout,
            extras = outcome.stagedExtras,
            primary = primary,
            expanded = expanded,
            big = big,
            fullFramesEmpty = outcome.fullFrames.isEmpty(),
            mediaRendererAvailable = runtime.mediaRender != null,
            matches = { view -> matcher?.matches(view) == true },
            mediaAreasAvailable = host::mediaAreasAvailable,
            demandOf = host::musicDemand,
            keyOf = host::key,
            seatLeft = session.seats::left,
            nativeSmallWidth = host::nativeSmallWidth,
            gap = scene.gap,
            clock = scene.clockWidth,
            battery = scene.batteryWidth,
            safeLeft = scene.safe.left,
            safeRight = scene.safe.right,
        )
        val decision = runtime.boundary.soft(session, IslandFeature.SPECTRUM, "evaluate small spectrum") {
            SpectrumAdmissionPolicy.evaluate(input)
        }
        if (decision == null) {
            releaseLyricGuard(session)
            return outcome
        }
        val admitted = runtime.boundary.soft(session, IslandFeature.SPECTRUM, "apply small spectrum") {
            when (decision) {
                SpectrumDecision.Unavailable -> Unit
                SpectrumDecision.NoCandidate -> {
                    record(session, "no native waveform: primary=${runtime.spectrumDescribe(primary)} " +
                        "extra=${outcome.stagedExtras.singleOrNull()?.let(runtime::spectrumDescribe)}")
                    releaseLyricGuard(session)
                }
                is SpectrumDecision.Rejected -> {
                    record(session, decision.reason)
                    releaseLyricGuard(session)
                }
                is SpectrumDecision.CoverOnly -> {
                    record(session, decision.reason)
                    releaseLyricGuard(session)
                }
                is SpectrumDecision.Admitted -> {
                    session.scene.musicSmall = decision.view
                    session.scene.fullFrames[decision.view] = decision.seat.frame
                    session.spectrum.returnGeometry = decision.returnGeometry
                    record(session, "nativeBig size=${decision.demand} currentLeft=${decision.seat.left} " +
                        "seat=${decision.seat.frame} compact=${decision.compactFallback}")
                    syncLyricGuard(session)
                }
            }
            decision
        }
        if (admitted == null) {
            val failed = decision as? SpectrumDecision.Admitted
            if (failed != null) session.scene.fullFrames.remove(failed.view)
            session.scene.musicSmall = null
            session.spectrum.returnGeometry = null
            releaseLyricGuard(session)
            return outcome
        }
        return outcome.copy(
            fullFrames = outcome.fullFrames + listOfNotNull(
                (admitted as? SpectrumDecision.Admitted)?.let { it.view to it.seat.frame },
            ),
            musicSmall = (admitted as? SpectrumDecision.Admitted)?.view,
        )
    }

    fun returningFrame(session: IslandSession, view: Any, x: Int): FullIslandFrame? {
        if (view in runtime.summaries(session)) return null
        val geometry = session.spectrum.returnGeometry?.takeIf { it.key == runtime.key(view) } ?: return null
        return geometry.target(x, host.nativeSmallWidth(view))
    }

    fun rewriteReturnRect(session: IslandSession, view: Any, rect: HostRect): HostRect? {
        if (!runtime.enabled(session)) return null
        val geometry = session.spectrum.returnGeometry?.takeIf { it.key == runtime.key(view) } ?: return null
        val frame = geometry.target(rect.left, rect.width) ?: return null
        return HostRect(frame.x, rect.top, frame.x + frame.width, rect.bottom)
    }

    fun syncLyricGuard(session: IslandSession) {
        val music = session.scene.musicSmall
        if (music == null) {
            releaseLyricGuard(session)
            return
        }
        val view = music as? View
        if (view == null || !host.lyricModuleInstalled(view)) {
            releaseLyricGuard(session)
            return
        }
        val roots = listOf(view)
        val current = session.spectrum.lyricGuard
        if (current != null && current.roots == roots) return
        current?.release()
        session.spectrum.lyricGuard = IslandLyricGuard(roots) {
            !runtime.retiring() && !session.health.failed &&
                session.presentation.expanded !== music && session.presentation.big !== music &&
                session.scene.musicSmall === music && session.scene.fullFrames[music]?.full == true
        }
    }

    fun releaseLyricGuard(session: IslandSession, view: Any? = null) {
        val guard = session.spectrum.lyricGuard ?: return
        if (view != null && guard.roots.none { it === view }) return
        guard.release()
        session.spectrum.lyricGuard = null
    }

    private fun record(session: IslandSession, message: String) {
        if (session.spectrum.lastLog == message) return
        session.spectrum.lastLog = message
        DebugLog.i(TAG, "small spectrum $message")
    }

    private companion object { const val TAG = "ThreeIslands" }
}
