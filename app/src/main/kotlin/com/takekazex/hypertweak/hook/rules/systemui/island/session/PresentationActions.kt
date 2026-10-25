package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession

/** Session capability; contains no host assembly or coordinator implementation. */
internal interface PresentationActions {
    fun fixedCutoutWidth(session: IslandSession?, view: Any): Int?
    fun clearBorrowedShadows(session: IslandSession): Unit
    fun prepareSmallMusicAnimation(session: IslandSession, view: Any): Unit
    fun beforeStateChange(session: IslandSession, view: Any): Unit
    fun afterStateChange(session: IslandSession, view: Any): Unit
    fun bigIslandAnimation(view: Any, args: Array<Any?>): TemplateAnimation
    fun resolveState(
        session: IslandSession,
        view: Any,
        before: Any?,
        after: Any?,
    ): Pair<Any?, Any?>
    fun projectedRenderState(session: IslandSession, view: Any, result: Any?): Any?
    fun currentBigIslandGeometry(session: IslandSession, view: Any, member: String): Int?
    fun pressTarget(session: IslandSession, view: Any, member: String): String?
    fun publishNativeReturn(session: IslandSession, view: Any): Unit
    fun prepareNativeMedia(session: IslandSession, view: Any): Boolean
}
