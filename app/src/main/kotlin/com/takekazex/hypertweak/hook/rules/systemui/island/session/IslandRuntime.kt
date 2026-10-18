package com.takekazex.hypertweak.hook.rules.systemui.island.session

import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.rules.systemui.island.IslandSettings
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMediaRenderContract
import com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMusicSpectrum
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.LayoutFlags
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandModePolicy
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandPresentation
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandRenderScope
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandTemplatePublication
import com.takekazex.hypertweak.util.DebugLog

/**
 * Composition root for the island session layer. Binders call coordinators; coordinators
 * share only this runtime, the narrow host ports and pure policy objects.
 */
internal class IslandRuntime(
    val host: IslandHostPorts,
    val preferences: IslandSettings,
    val retiring: () -> Boolean,
    val musicSpectrum: NativeMusicSpectrum?,
    val mediaRender: NativeMediaRenderContract?,
) {
    val sessions = SessionRegistry()
    val context = CallbackContext()
    val boundary = FeatureBoundary(context)
    val renderingState = IslandRenderScope()
    val templatePublication = IslandTemplatePublication()
    val relayoutPublication = IslandTemplatePublication()
    val layoutMeasurement = IslandTemplatePublication()
    // Restoration needs the host camera width even while an area lease is still held.
    val nativeCutoutRead = IslandTemplatePublication()
    // Only policy budgets read the unprojected cover width. Native rendering and
    // app-return callbacks must retain the paired width, even inside measurement.
    val nativeSmallWidthRead = IslandTemplatePublication()

    val mediaReturnPublication = IslandTemplatePublication()
    val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    val diagnostics by lazy { DiagnosticsCoordinator(this) }
    val spectrum by lazy { SpectrumCoordinator(this) }
    val templates by lazy { TemplateCoordinator(this) }
    val relayout by lazy { RelayoutCoordinator(this) }
    val settlement by lazy { SettlementCoordinator(this) }
    val measurement by lazy { MeasurementCoordinator(this) }
    val gestures by lazy { GestureCoordinator(this) }
    val coordinates by lazy { CoordinatesCoordinator(this) }
    val presentation by lazy { PresentationCoordinator(this) }

    init {
        boundary.restorer = { settlement.restore(it) }
    }

    fun budgetSmallWidth(view: Any): Int = nativeSmallWidthRead.run { host.nativeSmallWidth(view) }

    fun enabled(session: IslandSession): Boolean = IslandModePolicy.enabled(
        retiring = retiring(),
        failed = session.health.failed,
        suspended = session.lifecycle.suspended,
        threeIslands = preferences.threeIslands,
        landscapeFull = preferences.landscapeFull,
        landscapeMore = preferences.landscapeMore,
        removePlaceholder = preferences.removePlaceholder,
    )

    fun phoneEligible(session: IslandSession): Boolean {
        val owner = session.owner
        val bigHandler = host.handler(owner, IslandSlot.BIG) ?: return false
        return IslandModePolicy.phoneEligible(
            enabled = enabled(session),
            orientation = host.orientationOf(owner),
            smallestScreenWidthDp = host.smallestScreenWidthDp(owner),
            tinyScreen = host.isTinyScreen(owner),
            inConfigRebuild = host.inConfigRebuild(owner),
            showOncePresent = host.showOnce(owner) != null,
            tempShowPresent = host.tempShow(bigHandler) != null,
        )
    }

    fun flags(session: IslandSession): LayoutFlags = IslandModePolicy.flags(
        phoneEligible = phoneEligible(session),
        orientation = host.orientationOf(session.owner),
        threeIslands = preferences.threeIslands,
        landscapeFull = preferences.landscapeFull,
        landscapeMore = preferences.landscapeMore,
        removePlaceholder = preferences.removePlaceholder,
    )

    fun rowModeEnabled(session: IslandSession): Boolean = flags(session).rowMode

    fun landscapeFullEnabled(session: IslandSession): Boolean = flags(session).landscapeFull

    fun landscapeMoreEnabled(session: IslandSession): Boolean = flags(session).landscapeMore

    fun portraitCompactEnabled(session: IslandSession): Boolean = flags(session).portraitCompact

    fun summarySwipeEnabled(session: IslandSession): Boolean = flags(session).summarySwipe

    fun summaries(session: IslandSession): List<Any> = listOfNotNull(
        host.current(session.owner, IslandSlot.BIG),
        host.current(session.owner, IslandSlot.SMALL),
    ).plus(session.slot.extras).distinct()

    fun queuedSummaries(session: IslandSession): List<Any> = host.queue(session.owner).filter {
        host.stateKind(it) == com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandStateKind.HIDDEN
    }

    fun key(view: Any): String? = host.key(view)

    fun smallX(session: IslandSession, view: Any): Int? {
        spectrum.renderingReturnFrame(session, view)?.let { return it.x }
        val isExtra = view in session.slot.extras
        if (!isExtra && host.current(session.owner, IslandSlot.SMALL) !== view) {
            return session.interaction.departingPositions[view]
        }
        val layout = session.scene.layout ?: return null
        session.scene.fullFrames[view]?.let { return it.x }
        val left = session.seats.left(key(view))
            ?: if (isExtra) session.seats.extraOnLeft else session.seats.primaryOnLeft
        return if (left) layout.leftX else layout.rightX
    }

    fun sideWidth(session: IslandSession, view: Any): Int =
        if (fullTemplate(session, view)) checkNotNull(templateFrame(session, view)).width
        else host.nativeSmallWidth(view)

    fun templateFrame(session: IslandSession, view: Any) =
        spectrum.renderingReturnFrame(session, view) ?: session.scene.fullFrames[view]

    fun fullTemplate(session: IslandSession, view: Any): Boolean {
        if (!enabled(session)) return false
        spectrum.renderingReturnFrame(session, view)?.let { return it.full }
        return (rowModeEnabled(session) || session.scene.musicSmall === view) &&
            session.scene.fullFrames[view]?.full == true &&
            IslandPresentation.isSummary(view, session.scene.fullFrames.keys, summaries(session),
                host.current(session.owner, IslandSlot.EXPANDED))
    }

    fun fullSide(session: IslandSession, view: Any): Boolean =
        fullTemplate(session, view) &&
            (spectrum.renderingReturnFrame(session, view) != null ||
                view in session.slot.extras || host.current(session.owner, IslandSlot.SMALL) === view)

    fun portraitMain(session: IslandSession, view: Any): Boolean =
        spectrum.renderingReturnFrame(session, view) == null &&
            portraitCompactEnabled(session) && session.scene.portraitContent != null &&
            view === host.current(session.owner, IslandSlot.BIG) &&
            view !== host.current(session.owner, IslandSlot.EXPANDED)

    fun alignedTemplate(session: IslandSession, view: Any): Boolean =
        fullTemplate(session, view) || portraitMain(session, view)

    fun spectrumTarget(view: Any): Boolean = musicSpectrum?.matches(view) == true

    fun spectrumDescribe(view: Any?): String? = view?.let { musicSpectrum?.describe(it) }

    fun sessionForView(view: Any): IslandSession? = sessions.forContent(host, view)

    fun sessionForSwipe(coordinator: Any): IslandSession? = try {
        host.swipeCoordinator(coordinator)?.let(sessions::get)
    } catch (error: Throwable) {
        DebugLog.e(TAG, "resolve swipe owner", error)
        null
    }

    fun sessionForAnimation(animator: Any): IslandSession? =
        sessions.snapshot().firstOrNull { host.animationController(it.owner) === animator }

    fun isMainThread(): Boolean = Looper.myLooper() == main.looper

    private companion object { const val TAG = "ThreeIslands" }
}
