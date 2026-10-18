package com.takekazex.hypertweak.hook.rules.systemui.island.session

import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandFeature
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandPresentation
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandCameraSeat

internal sealed interface TemplateAnimation {
    /** `calculateBigIslandWidth` is animating native two-slot widths; swallow this call. */
    data object Suppress : TemplateAnimation
    data object Passthrough : TemplateAnimation
    data class Redirect(val args: Array<Any?>, val view: Any) : TemplateAnimation
}

/** Render-only SmallIsland -> BigIsland projection and template animation alignment. */
internal class PresentationCoordinator(private val runtime: IslandRuntime) {
    private val host get() = runtime.host

    fun fixedCutoutWidth(session: IslandSession?, view: Any): Int? {
        if (session != null) {
            return if (runtime.fullTemplate(session, view)) 0 else null
        }
        // Unknown owner (for example a Folme fake callback with no session mapping): the area
        // parent itself carries the camera-seat lease. Known sessions stay authoritative so
        // releaseFullPosition can still read the native cutout before the lease is removed.
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

    fun prepareSmallMusicAnimation(session: IslandSession, view: Any) {
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

    fun beforeStateChange(session: IslandSession, view: Any) {
        runtime.boundary.core(session, IslandFeature.PRESENTATION, "prepare native summary animation") {
            prepareSmallMusicAnimation(session, view)
            runtime.templates.releaseFullPosition(session, view)
        }
    }

    fun afterStateChange(session: IslandSession, view: Any) {
        runtime.boundary.core(session, IslandFeature.PRESENTATION, "remember native presentation") {
            session.presentation.fullPresentation[view] = runtime.fullSide(session, view)
        }
    }

    fun bigIslandAnimation(view: Any, args: Array<Any?>): TemplateAnimation {
        val session = runtime.sessionForView(view) ?: return TemplateAnimation.Passthrough
        if (runtime.layoutMeasurement.active) return TemplateAnimation.Suppress
        if (!runtime.alignedTemplate(session, view)) return TemplateAnimation.Passthrough
        val layout = session.scene.layout ?: return TemplateAnimation.Passthrough
        val frame = session.scene.fullFrames[view]
        val x = frame?.x ?: layout.bigX
        val width = frame?.width ?: layout.bigWidth
        val redirected = args.copyOf()
        redirected[3] = IslandPresentation.contentTranslation(
            layout.screenWidth, x.toFloat(), x.toFloat() + width,
            (args[3] as Number).toFloat(), 1f,
        )
        session.presentation.fullOrigins[view] = layout.screenWidth
        return TemplateAnimation.Redirect(redirected, view)
    }

    fun resolveState(
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

    fun projectedRenderState(session: IslandSession, view: Any, result: Any?): Any? =
        if (runtime.fullSide(session, view) && host.isSmallState(result)) host.renderBig(result) else null

    fun currentBigIslandGeometry(session: IslandSession, view: Any, member: String): Int? {
        if (!runtime.fullTemplate(session, view)) return null
        val frame = session.scene.fullFrames.getValue(view)
        return if (member == "getCurrentBigIslandX") frame.x else frame.width
    }

    fun pressTarget(session: IslandSession, view: Any, member: String): String? {
        if (!runtime.fullSide(session, view)) return null
        return if (member == "onPressSmallIsland") "onPressBigIsland" else "resetPressBigIsland"
    }

    fun prepareNativeMedia(session: IslandSession, view: Any): Boolean {
        var project = false
        runtime.boundary.soft(session, IslandFeature.SPECTRUM, "prepare native media renderer") {
            prepareSmallMusicAnimation(session, view)
            project = runtime.fullSide(session, view)
        }
        return project
    }
}
