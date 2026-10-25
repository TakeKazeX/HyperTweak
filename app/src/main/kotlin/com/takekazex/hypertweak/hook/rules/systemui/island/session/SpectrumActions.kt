package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.host.HostRect
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandScene
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandSession
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MediaReturnTarget
import com.takekazex.hypertweak.hook.rules.systemui.island.model.MeasurementOutcome
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame

/** Session capability; contains no host assembly or coordinator implementation. */
internal interface SpectrumActions {
    fun afterMeasure(
        session: IslandSession,
        outcome: MeasurementOutcome,
        scene: IslandScene,
        big: Any?,
    ): MeasurementOutcome
    fun returningFrame(session: IslandSession, view: Any, x: Int): FullIslandFrame?
    fun planReturn(session: IslandSession, view: Any, main: Any): MediaReturnTarget?
    fun rewriteReturnRect(session: IslandSession, view: Any, rect: HostRect): HostRect?
    fun nativeReturnTarget(session: IslandSession, view: Any): MediaReturnTarget?
    fun renderingReturnTarget(session: IslandSession, view: Any): MediaReturnTarget?
    fun renderingReturnFrame(session: IslandSession, view: Any): FullIslandFrame?
    fun hasReturnTarget(session: IslandSession, view: Any): Boolean
    fun cancelReturn(session: IslandSession, view: Any): Unit
    fun completeReturn(session: IslandSession, view: Any, completed: MediaReturnTarget?): Unit
    fun syncLyricGuard(session: IslandSession, candidate: Any? = null): Unit
    fun releaseLyricGuard(session: IslandSession, view: Any? = null): Unit
}
