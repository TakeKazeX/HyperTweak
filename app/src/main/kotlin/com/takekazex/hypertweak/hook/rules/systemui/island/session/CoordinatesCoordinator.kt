package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame

/** Read-only coordinate projections used by native animation and hit-region callbacks. */
internal class CoordinatesCoordinator(private val runtime: IslandRuntime) {
    private val host get() = runtime.host

    fun nativeCoordinateX(session: IslandSession, view: Any): Float? {
        val x = runtime.smallX(session, view) ?: return null
        val screenWidth = session.scene.layout?.screenWidth ?: return null
        return x + host.nativeSmallWidth(view) / 2f - screenWidth / 2f
    }

    fun nativeSmallTargetX(session: IslandSession): Int? {
        val small = host.current(session.owner, IslandSlot.SMALL) ?: return null
        return runtime.smallX(session, small)
    }

    fun smallX(session: IslandSession, view: Any): Int? = runtime.smallX(session, view)

    fun returningFrame(session: IslandSession, view: Any, x: Int): FullIslandFrame? =
        runtime.spectrum.returningFrame(session, view, x)

    fun smallRegionViews(session: IslandSession): List<Any> =
        listOfNotNull(host.current(session.owner, IslandSlot.SMALL)) + session.slot.extras

    fun needsRevealTarget(session: IslandSession): Boolean =
        host.current(session.owner, IslandSlot.BIG) == null &&
            session.scheduler.hasHidden && runtime.summarySwipeEnabled(session)

    fun extrasForLookup(session: IslandSession, operation: String, packageName: String?): List<Any> =
        session.slot.extras.filter { extra ->
            operation != "hasIsland" || host.packageName(extra) == packageName
        }

    fun containsExtra(session: IslandSession, view: Any): Boolean = view in session.slot.extras

    /** Side media publishes its paired frame width; all other summaries keep the native width. */
    fun publishedSmallWidth(session: IslandSession, view: Any): Int? =
        if (session.scene.musicSmall === view && session.scene.fullFrames[view]?.full == true) {
            session.scene.fullFrames.getValue(view).width
        } else {
            null
        }
}
