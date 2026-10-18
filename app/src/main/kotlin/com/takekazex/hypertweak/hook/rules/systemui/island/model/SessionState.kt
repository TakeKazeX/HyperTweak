package com.takekazex.hypertweak.hook.rules.systemui.island.model

import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandGestureScope
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandSlotLeases
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.IslandTemplateRebinds
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.spectrum.SmallIslandSpectrum
import com.takekazex.hypertweak.hook.rules.systemui.island.spectrum.IslandLyricGuard
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandRelayoutDriver
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandTemplateWidthLease
import java.util.WeakHashMap

/** Hidden-queue lease for the extra small islands this module schedules. */
internal class SlotLeaseState {
    val slot = IslandSlotLeases<Any>()
    var extras: List<Any>
        get() = slot.tasks
        set(value) {
            slot.stage(value)
        }
    val extra: Any? get() = extras.firstOrNull()
}

/** Scheduling diagnostics and capacity transition bookkeeping. */
internal class SchedulerState {
    var loggedKey: String? = null
    var moreCapacity = false
    var admissionLog: String? = null
}

/** Everything produced by a measurement pass and read by presentation/coordinates. */
internal class SceneState {
    var layout: ThreeIslandLayout? = null
    var layoutIntent = false
    var displayOrder = emptyList<String>()
    var rowAvailable = 0
    var rowGap = 0
    var portraitContent: IslandContentSize? = null
    var musicSmall: Any? = null
    val contentSizes = WeakHashMap<Any, IslandContentSize>()
    val fullFrames = LinkedHashMap<Any, FullIslandFrame>()
}

/** Gesture ownership and the positions captured before a task returns to the queue. */
internal class InteractionState {
    val gesture = IslandGestureScope()
    var swipePreview: Any? = null
    var summaryTracking: Boolean? = null
    val departingPositions = WeakHashMap<Any, Int>()
}

/** The native states this module may project from SmallIsland to BigIsland. */
internal class PresentationState {
    var expanded: Any? = null
    var big: Any? = null
    val fullPresentation = WeakHashMap<Any, Boolean>()
    val fullOrigins = WeakHashMap<Any, Int>()
}

/** Template leases, configuration bindings and the relayout driver are per owner. */
internal class TemplateState {
    var relayout: IslandRelayoutDriver? = null
    val templateWidths = IslandTemplateWidthLease()
    val configurationBindings = IslandTemplateRebinds()
}

internal class SessionLifecycle {
    var suspended = 0
}

/** Core failure disables the session; soft failure only degrades one feature. */
internal class SessionHealth {
    val failedFeatures = linkedSetOf<IslandFeature>()
    var coreFailed = false
    val failed: Boolean get() = coreFailed

    fun fail(feature: IslandFeature) {
        failedFeatures += feature
        if (!feature.soft) coreFailed = true
    }

    fun degrade(feature: IslandFeature) {
        failedFeatures += feature
    }

    fun isDegraded(feature: IslandFeature): Boolean = feature in failedFeatures
}

/** Spectrum-only state; never promotes a media failure to a session failure. */
internal class SpectrumState {
    var returnGeometry: SmallIslandSpectrum.ReturnGeometry? = null
    var lastLog: String? = null
    var lyricGuard: IslandLyricGuard? = null
    var lyricOwner: Any? = null
}
