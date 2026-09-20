package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import kotlin.math.cos
import kotlin.math.sin

/** The dots complete the missing sector of the same ring, after its compact transform. */
internal object DuoDotGeometry {
    private val angles = doubleArrayOf(125.0, 102.0, 78.0, 55.0)
    val radius = DuoDrawable.TRACK_RADIUS * DuoDrawable.COMPACT_RING_SCALE
    val x = angles.map { DuoDrawable.CENTER + radius * cos(Math.toRadians(it)).toFloat() }.toFloatArray()
    val y = angles.map { DuoDrawable.COMPACT_RING_CENTER_Y + radius * sin(Math.toRadians(it)).toFloat() }.toFloatArray()
}
