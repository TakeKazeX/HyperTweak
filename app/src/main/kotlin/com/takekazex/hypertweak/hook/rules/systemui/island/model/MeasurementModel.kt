package com.takekazex.hypertweak.hook.rules.systemui.island.model

import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.TemplateSeatMode
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout

/** Features that may be enabled independently by the three user-visible switches. */
internal data class LayoutFlags(
    val landscapeFull: Boolean,
    val landscapeMore: Boolean,
    val portraitCompact: Boolean,
    val rowMode: Boolean,
    val summarySwipe: Boolean,
)

/** The subset of the native calculation result the layout policy reads. */
internal data class NativeMeasureResult(
    val bigWidth: Int,
    val bigWidthWithSmall: Int,
    val bigXWithSmall: Int,
    val marginWidth: Int,
)

/** One template area pair must be leased for a published full frame or portrait main island. */
internal data class TemplateWidthRequest(
    val view: Any,
    val cutoutWidth: Int,
    val mode: TemplateSeatMode,
)

/**
 * Pure inputs for one measurement pass. `nativeSmallWidth`, `keyOf` and `seatLeft` are
 * side-effect-free lookups supplied by the coordinator.
 */
internal data class MeasureContext(
    val scene: IslandScene,
    val big: Any,
    val primary: Any?,
    val stagedExtras: List<Any>,
    val contentSizes: Map<Any, IslandContentSize>,
    val nativeSmallWidth: (Any) -> Int,
    val keyOf: (Any) -> String?,
    val seatLeft: (Any) -> Boolean?,
    val displayOrder: List<String>,
    val flags: LayoutFlags,
    val minBigWidth: Int,
    val nativeResult: NativeMeasureResult,
    val nativeMusicRight: Boolean,
    val layoutIntent: Boolean = true,
    val removePlaceholder: Boolean = true,
)

/** Complete layout decision for one measurement. Contains no host state or drawing. */
internal data class MeasurementOutcome(
    val layout: ThreeIslandLayout?,
    val fullFrames: Map<Any, FullIslandFrame>,
    val displayOrder: List<String>,
    val stagedExtras: List<Any>,
    val musicSmall: Any?,
    val portraitContent: IslandContentSize?,
    val layoutIntent: Boolean,
    val templateRequests: List<TemplateWidthRequest>,
)

/** Coordinates written into the host's nine-int calculation result. */
internal data class IslandResultGeometry(
    val width: Int,
    val left: Int,
    val right: Int,
    val x: Int,
    val margin: Int,
    val viewWidth: Int,
    val viewLeft: Int,
    val viewRight: Int,
    val viewX: Int,
)

/** The result of projecting the shared outcome onto one measured content view. */
internal sealed interface MeasurementRender {
    data object Skip : MeasurementRender
    data object Invalidate : MeasurementRender
    data class Apply(val geometry: IslandResultGeometry) : MeasurementRender
}
