package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandResultGeometry
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MeasureContext
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MeasurementOutcome
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MeasurementRender
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandLayoutPolicy

/** Measurement pipeline: capture the native size, plan pure geometry, lease templates, project. */
internal class MeasurementCoordinator(private val runtime: IslandRuntime) {
    private val host get() = runtime.host

    fun afterMeasure(view: Any, input: Any, result: Any, helper: Any): IslandResultGeometry? {
        val session = runtime.sessionForView(view) ?: return null
        return runtime.boundary.core(session, IslandFeature.MEASUREMENT, "measure three islands") {
            session.scene.contentSizes.remove(view)
            host.measuredContentSize(view, input)?.let { session.scene.contentSizes[view] = it }
            if (!session.scene.layoutIntent || !runtime.enabled(session)) return@core null
            val big = host.current(session.owner, IslandSlot.BIG)
            // A side-media measurement carries cutout=0. It must never publish that
            // value into the main template, including after media is promoted to BIG.
            val mainCutout = big?.let {
                runtime.nativeCutoutRead.run { host.fixedCutoutWidth(it) }
            }
            val scene = host.sceneOf(session.owner, input, host.orientationOf(session.owner)).let {
                if (big === view && mainCutout != null) it.copy(cutoutWidth = mainCutout) else it
            }
            val flags = runtime.flags(session)
            var outcome = outcomeFromSession(session)
            if (big === view) {
                session.scene.rowAvailable = scene.rowAvailable
                session.scene.rowGap = scene.gap
                val measured = IslandLayoutPolicy.measure(MeasureContext(
                    removePlaceholder = runtime.preferences.removePlaceholder,
                    scene = scene,
                    big = view,
                    primary = host.current(session.owner, IslandSlot.SMALL),
                    stagedExtras = session.slot.extras,
                    contentSizes = session.scene.contentSizes.toMap(),
                    nativeSmallWidth = host::nativeSmallWidth,
                    keyOf = host::key,
                    seatLeft = { candidate -> session.seats.left(host.key(candidate)) },
                    displayOrder = session.scene.displayOrder,
                    flags = flags,
                    minBigWidth = host.minimumBigWidth(helper),
                    nativeResult = host.nativeResult(result),
                    nativeMusicRight = runtime.preferences.threeIslands &&
                        host.current(session.owner, IslandSlot.SMALL)?.let(runtime::spectrumTarget) == true,
                ))
                session.scene.layout = measured.layout
                session.scene.fullFrames.clear()
                session.scene.fullFrames.putAll(measured.fullFrames)
                session.scene.displayOrder = measured.displayOrder
                session.scene.portraitContent = measured.portraitContent
                session.scene.musicSmall = null
                session.slot.extras = measured.stagedExtras
                outcome = runtime.spectrum.afterMeasure(session, measured, scene, view)
                session.scene.musicSmall = outcome.musicSmall
                session.scene.fullFrames.clear()
                session.scene.fullFrames.putAll(outcome.fullFrames)
            } else {
                outcome = outcomeFromSession(session)
            }
            IslandLayoutPolicy.requests(outcome, flags.rowMode, big, mainCutout ?: scene.cutoutWidth)
                .forEach { runtime.templates.acquire(session, it) }
            val render = IslandLayoutPolicy.render(
                outcome = outcome,
                view = view,
                big = big,
                isSummary = view in runtime.summaries(session),
                nativeMarginWidth = host.nativeResult(result).marginWidth,
                nativeCutoutWidth = scene.cutoutWidth,
            )
            when (render) {
                MeasurementRender.Skip -> null
                MeasurementRender.Invalidate -> {
                    session.scene.layout = null
                    session.scene.fullFrames.clear()
                    null
                }
                is MeasurementRender.Apply -> render.geometry
            }
        }
    }

    private fun outcomeFromSession(session: IslandSession): MeasurementOutcome = MeasurementOutcome(
        layout = session.scene.layout,
        fullFrames = session.scene.fullFrames.toMap(),
        displayOrder = session.scene.displayOrder,
        stagedExtras = session.slot.extras,
        musicSmall = session.scene.musicSmall,
        portraitContent = session.scene.portraitContent,
        layoutIntent = session.scene.layoutIntent,
        templateRequests = emptyList(),
    )
}
