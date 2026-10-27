package com.takekazex.hypertweak.hook.rules.systemui.island.session

import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.TemplateWidthRequest
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.TemplateSeatMode
import com.takekazex.hypertweak.util.logging.DebugLog

/** Area leasing, camera-seat release and configuration rebinds. */
internal class TemplateCoordinator(private val runtime: IslandSessionState) : TemplateActions {
    private val host get() = runtime.host

    override fun acquire(session: IslandSession, request: TemplateWidthRequest) {
        val areas = host.templateAreas(request.view).mapNotNull { pair ->
            val left = pair.left as? View ?: return@mapNotNull null
            val right = pair.right as? View ?: return@mapNotNull null
            left to right
        }
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
        runtime.spectrum.syncLyricGuard(session)
        val nativeCutout = runtime.nativeCutoutRead.run { host.fixedCutoutWidth(view) }
        session.template.templateWidths.release(view, nativeCutout)
        host.templateAreas(view).forEach { pair ->
            val left = pair.left as? View ?: return@forEach
            val right = pair.right as? View ?: return@forEach
            session.template.templateWidths.restoreCutout(left, right, nativeCutout)
        }
        (host.bigIslandView(view) as? View)?.let { root ->
            if (session.presentation.fullOrigins.remove(view) != null) root.translationX = 0f
        }
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
            val preserveMain = !configurationBound && runtime.enabled(session) &&
                runtime.portraitMain(session, view)
            val sideMusic = runtime.enabled(session) && runtime.preferences.threeIslands &&
                (view in session.slot.extras || host.current(owner, IslandSlot.SMALL) === view) &&
                view !== host.current(owner, IslandSlot.EXPANDED) && runtime.spectrumTarget(view)
            if (!sideMusic) session.scene.fullFrames.remove(view)
            // A hidden/app-return view may already own a side seat. Keep the guard scoped
            // to its summary area roots; main/expanded ownership releases it immediately.
            runtime.spectrum.syncLyricGuard(session)
            // initBigIslandView also runs on progress updates. Invalidating the last
            // published main geometry here makes releaseFullPosition reset translationX
            // before native measurement animates back to the very same destination.
            // A side binding must not invalidate the main island's geometry either.
            if (configurationBound || (view === host.current(owner, IslandSlot.BIG) && !preserveMain)) {
                session.scene.portraitContent = null
            }
            session.scene.contentSizes.remove(view)
            releaseFullPosition(session, view)
            if (configurationBound) {
                DebugLog.d(TAG, "configuration template bound; frame generation invalidated")
                runtime.relayout.request(session)
            } else if (preserveMain) {
                val cutout = runtime.nativeCutoutRead.run { host.fixedCutoutWidth(view) }
                acquire(session, TemplateWidthRequest(view, cutout, TemplateSeatMode.NORMAL))
            } else if (sideMusic && session.scene.musicSmall === view) {
                acquire(session, TemplateWidthRequest(view, 0, TemplateSeatMode.CAMERA_RELEASING))
            }
        }
    }

    private companion object { const val TAG = "ThreeIslands" }
}
