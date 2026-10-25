package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession

/** Session capability; contains no host assembly or coordinator implementation. */
internal interface DiagnosticsActions {
    fun register(session: IslandSession): Unit
    fun retire(): Unit
}
