package com.takekazex.hypertweak.hook.rules.systemui.island.session

import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.TemplateWidthRequest
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.TemplateSeatMode
import com.takekazex.hypertweak.util.DebugLog

/** Area leasing, camera-seat release and configuration rebinds. */
internal class TemplateCoordinator(private val runtime: IslandRuntime) {
    private val host get() = runtime.host

    fun acquire(session: IslandSession, request: TemplateWidthRequest) {
        val areas = host.templateAreas(request.view)
        if (areas.isEmpty()) return
        areas.forEach { pair ->
            val left = pair.left as? View ?: return@forEach
            val right = pair.right as? View ?: return@forEach
            when (request.mode) {
                TemplateSeatMode.CAMERA_RELEASING ->
                    session.template.templateWidths.acquireMedia(request.view, left, right)
                TemplateSeatMode.NORMAL ->
                    session.template.templateWidths.acquire(request.view, left, right, request.cutoutWidth)
            }
        }
    }

    fun releaseFullPosition(session: IslandSession, view: Any) {
        if (runtime.alignedTemplate(session, view)) return
        runtime.spectrum.releaseLyricGuard(session, view)
        val nativeCutout = host.fixedCutoutWidth(view)
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

    fun templateBound(view: Any, binding: Any = view) {
        if (runtime.retiring()) return
        if (!runtime.isMainThread()) {
            runtime.main.post { templateBound(view, binding) }
            return
        }
        val session = runtime.sessionForView(view) ?: return
        runtime.boundary.core(session, IslandFeature.TEMPLATE, "publish rebound template") {
            val owner = session.owner
            val sideMusic = runtime.enabled(session) && runtime.preferences.threeIslands &&
                (view in session.slot.extras || host.current(owner, IslandSlot.SMALL) === view) &&
                view !== host.current(owner, IslandSlot.EXPANDED) && runtime.spectrumTarget(view)
            if (!sideMusic) session.scene.fullFrames.remove(view)
            if (!sideMusic) runtime.spectrum.releaseLyricGuard(session, view)
            session.scene.portraitContent = null
            session.scene.contentSizes.remove(view)
            releaseFullPosition(session, view)
            if (session.template.configurationBindings.bound(binding)) {
                DebugLog.d(TAG, "configuration template bound; frame generation invalidated")
                runtime.relayout.request(session)
            } else if (sideMusic && session.scene.musicSmall === view) {
                acquire(session, TemplateWidthRequest(view, 0, TemplateSeatMode.CAMERA_RELEASING))
            }
        }
    }

    private companion object { const val TAG = "ThreeIslands" }
}
