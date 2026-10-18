package com.takekazex.hypertweak.hook.rules.systemui.island.session

import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.island.host.HostRect
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandScene
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MediaReturnTarget
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MeasurementOutcome
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SpectrumAdmissionInput
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SpectrumDecision
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.PortraitIslandLayout
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.spectrum.SmallIslandSpectrum
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
        val primary = host.current(session.owner, IslandSlot.SMALL)
        val previous = session.spectrum.returnGeometry
        val rememberedSidePresent = previous != null &&
            (listOfNotNull(primary) + outcome.stagedExtras).any { host.key(it) == previous.key && it !== big }
        // Leaving a side seat for AppExpanded/BIG is not a rejection of its return plan.
        // Only a new layout that actually budgets this media side may replace or revoke it.
        if (rememberedSidePresent) session.spectrum.returnGeometry = null
        val layout = outcome.layout
        if (layout == null) {
            syncLyricGuard(session)
            return outcome
        }
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
            nativeSmallWidth = runtime::budgetSmallWidth,
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
            syncLyricGuard(session)
            return outcome
        }
        val admitted = runtime.boundary.soft(session, IslandFeature.SPECTRUM, "apply small spectrum") {
            when (decision) {
                SpectrumDecision.Unavailable -> Unit
                SpectrumDecision.NoCandidate -> {
                    record(session, "no native waveform: primary=${runtime.spectrumDescribe(primary)} " +
                        "extra=${outcome.stagedExtras.singleOrNull()?.let(runtime::spectrumDescribe)}")
                    syncLyricGuard(session)
                }
                is SpectrumDecision.Rejected -> {
                    record(session, decision.reason)
                    syncLyricGuard(session)
                }
                is SpectrumDecision.CoverOnly -> {
                    record(session, decision.reason)
                    syncLyricGuard(session)
                }
                is SpectrumDecision.Admitted -> {
                    session.scene.musicSmall = decision.view
                    session.scene.fullFrames[decision.view] = decision.seat.frame
                    session.spectrum.returnGeometry = decision.returnGeometry.copy(screenHeight = scene.screenHeight)
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
            if (rememberedSidePresent) session.spectrum.returnGeometry = null
            syncLyricGuard(session)
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
        if (!returnEnabled(session) || view in runtime.summaries(session)) return null
        val geometry = session.spectrum.returnGeometry?.takeIf { it.key == runtime.key(view) } ?: return null
        return geometry.target(x, host.nativeSmallWidth(view))
    }

    /** Rebase a saved side plan against the native query's future main task, not today's BIG music. */
    fun planReturn(session: IslandSession, view: Any, main: Any): MediaReturnTarget? {
        if (!returnEnabled(session)) return null
        val previous = session.spectrum.returnGeometry?.takeIf { it.key == host.key(view) } ?: return null
        val left = previous.seatLeft ?: return null
        val content = session.spectrum.returnContents[host.key(main)] ?: return null
        if (previous.screenHeight <= 0) return null
        val other = runtime.summaries(session).firstOrNull { it !== view && it !== main }
        val otherWidth = other?.let(runtime::budgetSmallWidth) ?: 0
        val small = runtime.budgetSmallWidth(view)
        val cutout = runtime.nativeCutoutRead.run { host.fixedCutoutWidth(main) }
        val layout = PortraitIslandLayout.calculate(
            previous.layout.screenWidth, previous.screenHeight,
            previous.clock, previous.battery, content, cutout,
            if (left) small else otherWidth, previous.gap,
            left || other != null, !left || other != null,
            previous.safeLeft, previous.safeRight, if (left) otherWidth else small,
        )
        session.spectrum.returnGeometry = layout?.let { previous.copy(layout = it, otherWidth = otherWidth) }
        // Freeze either a paired frame or an explicitly budgeted native cover. An old
        // paired scene must not override a future row that has already rejected the wave.
        val paired = layout?.let { SmallIslandSpectrum.choose(it, previous.content, previous.gap,
            previous.clock, previous.battery, previous.safeLeft, previous.safeRight, left, otherWidth)?.frame }
        val frame = paired ?: layout?.takeIf { small > 0 }?.let {
            FullIslandFrame(if (left) it.leftX else it.rightX, IslandContentSize(small, 0), full = false)
        }
        return frame?.let { MediaReturnTarget(previous.key, it, previous.layout.screenWidth) }
            .also { session.spectrum.returnTarget = it }
    }

    fun rewriteReturnRect(session: IslandSession, view: Any, rect: HostRect): HostRect? {
        if (!returnEnabled(session)) return null
        session.spectrum.returnTarget?.takeIf {
            it.key == host.key(view) && it.frame.x == rect.left && it.frame.width == rect.width
        }?.let { return rect }
        val geometry = session.spectrum.returnGeometry?.takeIf { it.key == runtime.key(view) } ?: return null
        val frame = geometry.target(rect.left, rect.width) ?: return null
        session.spectrum.returnTarget = MediaReturnTarget(geometry.key, frame, geometry.layout.screenWidth)
        return HostRect(frame.x, rect.top, frame.x + frame.width, rect.bottom)
    }

    private fun returnEnabled(session: IslandSession): Boolean =
        runtime.enabled(session) && runtime.preferences.threeIslands &&
            host.orientationOf(session.owner) == IslandOrientation.PORTRAIT

    // Layout eligibility temporarily disappears during configuration/app transitions. That
    // must not reactivate compact suppression inside a requested landscape full template.
    private fun landscapeLyricsRequested(session: IslandSession): Boolean =
        runtime.preferences.landscapeFull &&
            host.orientationOf(session.owner) == IslandOrientation.LANDSCAPE

    /** Capture only targets valid for the current mode before choosing a native renderer. */
    fun nativeReturnTarget(session: IslandSession, view: Any): MediaReturnTarget? =
        (runtime.renderingState.returnTarget().takeIf { runtime.renderingState.get() === view }
            ?: session.spectrum.returnTarget)?.takeIf {
            returnEnabled(session) && it.key == host.key(view)
        }

    /** A return target is only a render projection; scheduling keeps the actual host states. */
    fun renderingReturnTarget(session: IslandSession, view: Any): MediaReturnTarget? =
        nativeReturnTarget(session, view)?.takeIf { runtime.renderingState.get() === view }

    fun renderingReturnFrame(session: IslandSession, view: Any): FullIslandFrame? =
        renderingReturnTarget(session, view)?.frame

    fun hasReturnTarget(session: IslandSession, view: Any): Boolean =
        nativeReturnTarget(session, view) != null

    fun cancelReturn(session: IslandSession, view: Any) {
        if (session.spectrum.returnTarget?.key == host.key(view)) session.spectrum.returnTarget = null
    }

    /** Let all synchronous real/fake close-end callbacks finish before retiring the pin. */
    fun completeReturn(session: IslandSession, view: Any, completed: MediaReturnTarget?) {
        val target = completed?.takeIf { it.key == host.key(view) } ?: return
        runtime.main.post {
            if (session.spectrum.returnTarget !== target) return@post
            session.spectrum.returnTarget = null
            runtime.boundary.soft(session, IslandFeature.SPECTRUM, "finish native media return") {
                runtime.templates.releaseFullPosition(session, view)
                if (session.scene.fullFrames[view] != target.frame) runtime.relayout.request(session)
            }
        }
    }

    internal fun sideLyricTarget(session: IslandSession, view: Any): Boolean =
        !runtime.retiring() && runtime.enabled(session) && runtime.preferences.threeIslands &&
            !landscapeLyricsRequested(session) &&
            (hasReturnTarget(session, view) ||
                host.current(session.owner, IslandSlot.EXPANDED) !== view &&
                host.current(session.owner, IslandSlot.BIG) !== view) && runtime.spectrumTarget(view) &&
            (session.scene.musicSmall === view || view in session.slot.extras ||
                host.current(session.owner, IslandSlot.SMALL) === view ||
                session.spectrum.returnGeometry?.key == runtime.key(view))

    /** Keep the real and fake summary templates guarded through app-return, not the card. */
    fun syncLyricGuard(session: IslandSession, candidate: Any? = null) {
        runtime.boundary.soft(session, IslandFeature.SPECTRUM, "synchronize side lyric guard") {
            syncLyricGuardInternal(session, candidate)
        }
    }

    private fun syncLyricGuardInternal(session: IslandSession, candidate: Any?) {
        val music = candidate?.takeIf { sideLyricTarget(session, it) }
            ?: session.scene.musicSmall ?: session.spectrum.lyricOwner
        val view = music as? View
        if (view == null || !sideLyricTarget(session, music) || !host.lyricModuleInstalled(view)) {
            releaseLyricGuard(session)
            return
        }
        val roots = host.templateAreas(music).mapNotNull { (it.left as? View)?.parent as? View }.distinct()
        if (roots.isEmpty()) {
            releaseLyricGuard(session)
            return
        }
        val current = session.spectrum.lyricGuard
        if (current == null || current.roots != roots || session.spectrum.lyricOwner !== music) {
            current?.release()
            session.spectrum.lyricOwner = music
            session.spectrum.lyricGuard = IslandLyricGuard(roots, active = { sideLyricTarget(session, music) })
        }
        session.spectrum.lyricGuard?.applyNow()
    }

    fun releaseLyricGuard(session: IslandSession, view: Any? = null) {
        if (view != null && session.spectrum.lyricOwner !== view) return
        session.spectrum.lyricGuard?.release()
        session.spectrum.lyricGuard = null
        session.spectrum.lyricOwner = null
    }

    private fun record(session: IslandSession, message: String) {
        if (session.spectrum.lastLog == message) return
        session.spectrum.lastLog = message
        DebugLog.i(TAG, "small spectrum $message")
    }

    private companion object { const val TAG = "ThreeIslands" }
}
