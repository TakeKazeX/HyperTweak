package com.takekazex.hypertweak.hook.rules.systemui.island.session

import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandStateKind
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.TemplateDeparture
import com.takekazex.hypertweak.hook.rules.systemui.island.model.TemplateWidthRequest
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.TemplateSeatMode
import com.takekazex.hypertweak.util.logging.DebugLog

/** Full-template area leasing, camera-seat release, exit deferral and configuration rebinds. */
internal class TemplateCoordinator(private val runtime: IslandSessionState) : TemplateActions {
    private val host get() = runtime.host

    override fun acquire(session: IslandSession, request: TemplateWidthRequest) {
        val areas = host.templateAreas(request.view).mapNotNull { pair ->
            val left = pair.left as? View ?: return@mapNotNull null
            val right = pair.right as? View ?: return@mapNotNull null
            left to right
        }
        // A full template owns the area pair outright; drop a camera-seated snapshot first.
        runtime.compact.forget(request.view)
        session.presentation.departures.remove(request.view)
        // Ordinary content updates can replace area roots without replacing the visible
        // island. Retire only detached pairs; keep the current pair and its animation.
        session.template.templateWidths.retain(request.view, areas)
        if (areas.isEmpty()) return
        areas.forEach { (left, right) ->
            when (request.mode) {
                TemplateSeatMode.CAMERA_RELEASING ->
                    session.template.templateWidths.acquireMedia(request.view, left, right)
                TemplateSeatMode.NORMAL ->
                    session.template.templateWidths.acquire(request.view, left, right, request.cutoutWidth)
            }
        }
    }

    override fun releaseFullPosition(session: IslandSession, view: Any) {
        if (runtime.alignedTemplate(session, view)) return
        session.presentation.departures.remove(view)
        runtime.spectrum.syncLyricGuard(session)
        // A compact main template is a different owner. Generic row cleanup must not
        // overwrite its camera seat before every native measure/state callback.
        if (runtime.compact.holds(view) && !session.template.templateWidths.holds(view) &&
            view !in session.presentation.fullOrigins) return
        val nativeCutout = runtime.nativeCutoutRead.run { host.fixedCutoutWidth(view) }
        session.template.templateWidths.release(view, nativeCutout)
        host.templateAreas(view).forEach { pair ->
            val left = pair.left as? View ?: return@forEach
            val right = pair.right as? View ?: return@forEach
            session.template.templateWidths.restoreCutout(left, right, nativeCutout)
        }
        if (session.presentation.fullOrigins.remove(view) != null) {
            (host.bigIslandView(view) as? View)?.translationX = 0f
        }
    }

    /**
     * Before a native transition. A full template that is hidden or deleted keeps its lease and
     * offset until its own exit animation ends; restoring them first would make the exit start
     * from the native centred template. A view promoted to the main or expanded template is
     * released at once so that it never animates into the camera seat with a zero cutout.
     */
    override fun departFullPosition(session: IslandSession, view: Any) {
        if (runtime.alignedTemplate(session, view)) {
            session.presentation.departures.remove(view)
            return
        }
        val held = session.presentation.fullOrigins.containsKey(view) || session.template.templateWidths.holds(view)
        val owner = session.owner
        val leaving = held && host.current(owner, IslandSlot.EXPANDED) !== view &&
            host.current(owner, IslandSlot.BIG) !== view &&
            host.stateKind(view).let { it == IslandStateKind.HIDDEN || it == IslandStateKind.OTHER }
        if (!leaving) {
            releaseFullPosition(session, view)
            return
        }
        if (session.presentation.departures.containsKey(view)) return
        val departure = TemplateDeparture()
        session.presentation.departures[view] = departure
        runtime.main.postDelayed({ finishDeparture(session, view, departure, "timeout") }, DEPARTURE_TIMEOUT_MS)
    }

    override fun animationEvent(session: IslandSession, view: Any, tag: String, type: String) {
        val departure = session.presentation.departures[view] ?: return
        when (type) {
            "ANIM_START" -> if (departure.tag == null) departure.tag = tag
            "ANIM_FINISH", "ANIM_CANCEL" -> if (departure.tag == tag) finishDeparture(session, view, departure, type)
        }
    }

    private fun finishDeparture(session: IslandSession, view: Any, departure: TemplateDeparture, reason: String) {
        if (session.presentation.departures[view] !== departure) return
        runtime.boundary.core(session, IslandFeature.TEMPLATE, "release departed full template") {
            session.presentation.departures.remove(view)
            DebugLog.d(TAG, "full template departure released reason=$reason tag=${departure.tag}")
            releaseFullPosition(session, view)
        }
    }

    override fun releasedPadding(factory: Any, leftSide: Boolean): Int {
        runtime.sessions.snapshot().forEach { session ->
            val widths = session.template.templateWidths
            widths.owners().forEach { owner ->
                if (host.templateFactory(owner) === factory) return widths.releasedPadding(owner, leftSide)
            }
        }
        return 0
    }

    override fun templateBound(view: Any, binding: Any) {
        if (runtime.retiring()) return
        if (!runtime.isMainThread()) {
            runtime.main.post { templateBound(view, binding) }
            return
        }
        val session = runtime.sessionForView(view) ?: return
        runtime.boundary.core(session, IslandFeature.TEMPLATE, "publish rebound template") {
            val owner = session.owner
            val configurationBound = session.template.configurationBindings.bound(binding)
            val sideMusic = runtime.enabled(session) && runtime.preferences.threeIslands &&
                (view in session.slot.extras || host.current(owner, IslandSlot.SMALL) === view) &&
                view !== host.current(owner, IslandSlot.EXPANDED) && runtime.spectrumTarget(view)
            if (!sideMusic) session.scene.fullFrames.remove(view)
            // A hidden/app-return view may already own a side seat. Keep the guard scoped
            // to its summary area roots; main/expanded ownership releases it immediately.
            runtime.spectrum.syncLyricGuard(session)
            // initBigIslandView also runs on progress updates. Main geometry stays published until
            // the native measurement that follows replaces it; only a rebuild invalidates it.
            if (configurationBound) session.scene.portraitContent = null
            session.scene.contentSizes.remove(view)
            releaseFullPosition(session, view)
            if (configurationBound) {
                DebugLog.d(TAG, "configuration template bound; frame generation invalidated")
                runtime.relayout.request(session)
            } else if (sideMusic && session.scene.musicSmall === view) {
                acquire(session, TemplateWidthRequest(view, 0, TemplateSeatMode.CAMERA_RELEASING))
            }
        }
    }

    private companion object {
        const val TAG = "ThreeIslands"
        // Native island transitions finish well within this; it only covers a lost callback.
        const val DEPARTURE_TIMEOUT_MS = 1200L
    }
}
