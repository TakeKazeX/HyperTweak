package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.host.TouchInput
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession

/** Session capability; contains no host assembly or coordinator implementation. */
internal interface GestureActions {
    fun routeWindowTouch(session: IslandSession, touch: TouchInput): Unit
    fun claimNativeClick(session: IslandSession, view: Any): Unit
    fun performClick(session: IslandSession): Boolean
    fun shouldBlockCollapse(session: IslandSession, reason: String): Boolean
    fun shouldCaptureSwipe(session: IslandSession): Boolean
    fun handleAppEvent(session: IslandSession, visible: List<Any>, event: Any): Boolean
    fun handleSwipe(session: IslandSession, x: Float, y: Float): Boolean
    fun resetSwipe(session: IslandSession): Boolean
}
