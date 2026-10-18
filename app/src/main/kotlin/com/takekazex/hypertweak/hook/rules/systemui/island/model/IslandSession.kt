package com.takekazex.hypertweak.hook.rules.systemui.island.model

import com.takekazex.hypertweak.hook.rules.systemui.island.policy.SmallIslandSeats
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.SummaryIslandScheduler

/** One native coordinator's island state, grouped by ownership instead of a flat field bag. */
internal class IslandSession(val owner: Any) {
    val slot = SlotLeaseState()
    val scheduler = SummaryIslandScheduler()
    val schedulerState = SchedulerState()
    val scene = SceneState()
    val interaction = InteractionState()
    val presentation = PresentationState()
    val template = TemplateState()
    val lifecycle = SessionLifecycle()
    val health = SessionHealth()
    val seats = SmallIslandSeats()
    val spectrum = SpectrumState()
}

/** Failures are feature-scoped: only core features restore and disable the session. */
internal enum class IslandFeature {
    SCHEDULING,
    MEASUREMENT,
    TEMPLATE,
    PRESENTATION,
    TOUCH,
    SPECTRUM,
    LYRIC_GUARD,
    ;

    val soft: Boolean get() = this == SPECTRUM || this == LYRIC_GUARD
}
