package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.TemplateWidthRequest

/** Session capability; contains no host assembly or coordinator implementation. */
internal interface TemplateActions {
    fun acquire(session: IslandSession, request: TemplateWidthRequest): Unit
    fun releaseFullPosition(session: IslandSession, view: Any): Unit
    fun templateBound(view: Any, binding: Any = view): Unit
}
