package com.takekazex.hypertweak.hook.rules.systemui.island.session

import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandResultGeometry

/** Session capability; contains no host assembly or coordinator implementation. */
internal interface MeasurementActions {
    fun afterMeasure(view: Any, input: Any, result: Any, helper: Any): IslandResultGeometry?
}
