package com.takekazex.hypertweak.dock

import kotlin.math.ceil

object DockCaptureGeometry {
    fun rotation(windowRotation: Int, installOrientation: Int): Int? =
        if (windowRotation !in 0..3 || installOrientation !in 0..3) null else (windowRotation + installOrientation) % 4
    fun buffer(width: Int, height: Int, rotation: Int, scale: Float): Pair<Int, Int>? {
        if (width !in 1..8192 || height !in 1..8192 || rotation !in 0..3 || !scale.isFinite() || scale <= 0 || scale > 1) return null
        val swapped = rotation % 2 != 0
        return ceil((if (swapped) height else width) * scale.toDouble()).toInt() to
            ceil((if (swapped) width else height) * scale.toDouble()).toInt()
    }
}
