package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession

/** Session capability; contains no host assembly or coordinator implementation. */
internal interface RelayoutActions {
    fun prepare(session: IslandSession): Unit
    fun publish(session: IslandSession): Unit
    fun request(session: IslandSession): Unit
}
