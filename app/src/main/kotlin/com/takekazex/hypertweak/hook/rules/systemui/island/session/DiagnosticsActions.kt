package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession

/** Session capability; contains no host assembly or coordinator implementation. */
internal interface DiagnosticsActions {
    val tracing: Boolean
    fun register(session: IslandSession): Unit
    fun trace(session: IslandSession, view: Any?, event: String, details: String = ""): Unit
    fun sampleAnimation(session: IslandSession, view: Any): Unit
    fun retire(): Unit
}
