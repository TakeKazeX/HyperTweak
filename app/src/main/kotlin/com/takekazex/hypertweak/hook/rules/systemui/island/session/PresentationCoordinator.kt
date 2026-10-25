package com.takekazex.hypertweak.hook.rules.systemui.island.session

import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.TemplateWidthRequest
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.TemplateSeatMode
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandPresentation
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandCameraSeat

/** Render-only SmallIsland -> BigIsland projection and template animation alignment. */
internal class PresentationCoordinator(private val runtime: IslandSessionState) : PresentationActions {
    private val host get() = runtime.host

    override fun fixedCutoutWidth(session: IslandSession?, view: Any): Int? {
        if (runtime.nativeCutoutRead.active) return null
        if (session != null && runtime.fullTemplate(session, view)) return 0
        // The native animation may temporarily remove a summary from its handler. A held
        // real/fake area lease remains authoritative until releaseFullPosition restores it.
        val leased = try {
            host.templateAreas(view).any { pair ->
                (pair.left as? View)?.parent?.let(IslandCameraSeat::holds) == true
            }
        } catch (error: Throwable) {
            DebugLog.w("ThreeIslands", "inspect camera seat", error)
            false
        }
        return if (leased) 0 else null
    }

    override fun clearBorrowedShadows(session: IslandSession) {
        val expanded = host.current(session.owner, IslandSlot.EXPANDED)
        session.slot.extras.filter { it !== expanded }.forEach { view ->
            host.call(view, "clearShadow")
            host.fakeView(view)?.let { host.call(it, "clearShadow") }
        }
    }

    override fun prepareSmallMusicAnimation(session: IslandSession, view: Any) {
        if (session.scene.musicSmall === view && session.scene.fullFrames[view]?.full == true) return
        if (!runtime.isMainThread() || runtime.layoutMeasurement.active ||
            runtime.context.measuringView != null || !runtime.enabled(session) ||
            !session.scene.layoutIntent || session.scene.layout?.full != false ||
            session.slot.extras.size > 1 || !runtime.preferences.threeIslands ||
            view === host.current(session.owner, IslandSlot.EXPANDED) ||
            (view !in session.slot.extras && view !== host.current(session.owner, IslandSlot.SMALL)) ||
            !runtime.spectrumTarget(view)
        ) {
            return
        }
        val big = host.current(session.owner, IslandSlot.BIG) ?: return
        runtime.layoutMeasurement.run {
            host.call(view, "calculateBigIslandWidth")
            host.call(big, "calculateBigIslandWidth")
            host.call(view, "calculateBigIslandWidth")
        }
    }

    override fun beforeStateChange(session: IslandSession, view: Any) {
        runtime.boundary.core(session, IslandFeature.PRESENTATION, "prepare native summary animation") {
            prepareSmallMusicAnimation(session, view)
            runtime.templates.releaseFullPosition(session, view)
        }
    }

    override fun afterStateChange(session: IslandSession, view: Any) {
        runtime.boundary.core(session, IslandFeature.PRESENTATION, "remember native presentation") {
            session.presentation.fullPresentation[view] = runtime.fullSide(session, view)
        }
    }

    override fun bigIslandAnimation(view: Any, args: Array<Any?>): TemplateAnimation {
        val session = runtime.sessionForView(view) ?: return TemplateAnimation.Passthrough
        if (runtime.layoutMeasurement.active) return TemplateAnimation.Suppress
        if (!runtime.alignedTemplate(session, view)) return TemplateAnimation.Passthrough
        val target = runtime.spectrum.renderingReturnTarget(session, view)
        val layout = session.scene.layout
        if (layout == null && target == null) return TemplateAnimation.Passthrough
        val frame = runtime.templateFrame(session, view)
        val x = frame?.x ?: checkNotNull(layout).bigX
        val width = frame?.width ?: checkNotNull(layout).bigWidth
        val screenWidth = target?.screenWidth ?: checkNotNull(layout).screenWidth
        val redirected = args.copyOf()
        redirected[3] = IslandPresentation.contentTranslation(
            screenWidth, x.toFloat(), x.toFloat() + width,
            (args[3] as Number).toFloat(), 1f,
        )
        session.presentation.fullOrigins[view] = screenWidth
        return TemplateAnimation.Redirect(redirected, view)
    }

    override fun resolveState(
        session: IslandSession,
        view: Any,
        before: Any?,
        after: Any?,
    ): Pair<Any?, Any?> {
        val previousFull = session.presentation.fullPresentation[view] == true
        val nextFull = runtime.fullSide(session, view)
        return IslandPresentation.project(before, previousFull, host::isSmallState) { host.renderBig(before) } to
            IslandPresentation.project(after, nextFull, host::isSmallState) { host.renderBig(after) }
    }

    override fun projectedRenderState(session: IslandSession, view: Any, result: Any?): Any? =
        if (runtime.fullSide(session, view) && host.isSmallState(result)) host.renderBig(result) else null

    override fun currentBigIslandGeometry(session: IslandSession, view: Any, member: String): Int? {
        if (!runtime.fullTemplate(session, view)) return null
        val frame = checkNotNull(runtime.templateFrame(session, view))
        return if (member == "getCurrentBigIslandX") frame.x else frame.width
    }

    override fun pressTarget(session: IslandSession, view: Any, member: String): String? {
        if (!runtime.fullSide(session, view)) return null
        return if (member == "onPressSmallIsland") "onPressBigIsland" else "resetPressBigIsland"
    }

    /** Publish the same target into native fields before any real/fake app-close renderer reads them. */
    override fun publishNativeReturn(session: IslandSession, view: Any) {
        val frame = runtime.spectrum.renderingReturnFrame(session, view) ?: return
        if (runtime.mediaReturnPublication.active) return
        if (!frame.full) {
            runtime.templates.releaseFullPosition(session, view)
            return
        }
        runtime.mediaReturnPublication.run {
            runtime.boundary.soft(session, IslandFeature.SPECTRUM, "publish native media return geometry") {
                runtime.templates.acquire(session,
                    TemplateWidthRequest(view, 0, TemplateSeatMode.CAMERA_RELEASING))
                runtime.layoutMeasurement.run { host.call(view, "calculateBigIslandWidth") }
                // Native measurement synchronously publishes updateBigIslandLayout itself.
            }
        }
    }

    override fun prepareNativeMedia(session: IslandSession, view: Any): Boolean {
        runtime.spectrum.nativeReturnTarget(session, view)?.let { return it.frame.full }
        var project = false
        runtime.boundary.soft(session, IslandFeature.SPECTRUM, "prepare native media renderer") {
            prepareSmallMusicAnimation(session, view)
            project = runtime.fullSide(session, view)
        }
        return project
    }
}
