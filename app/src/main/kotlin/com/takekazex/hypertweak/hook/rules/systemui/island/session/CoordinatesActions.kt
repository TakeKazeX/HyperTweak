package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame

/** Session capability; contains no host assembly or coordinator implementation. */
internal interface CoordinatesActions {
    fun nativeCoordinateX(session: IslandSession, view: Any): Float?
    fun nativeSmallTargetX(session: IslandSession): Int?
    fun smallX(session: IslandSession, view: Any): Int?
    fun returningFrame(session: IslandSession, view: Any, x: Int): FullIslandFrame?
    fun smallRegionViews(session: IslandSession): List<Any>
    fun needsRevealTarget(session: IslandSession): Boolean
    fun extrasForLookup(session: IslandSession, operation: String, packageName: String?): List<Any>
    fun containsExtra(session: IslandSession, view: Any): Boolean
    fun publishedSmallWidth(session: IslandSession, view: Any): Int?
}
