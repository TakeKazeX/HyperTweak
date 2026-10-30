package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

internal object DuoTapPolicy {
    fun isTap(startX: Float, startY: Float, x: Float, y: Float, slop: Float,
              duration: Long, timeout: Long): Boolean =
        duration in 0 until timeout && kotlin.math.abs(x - startX) <= slop &&
            kotlin.math.abs(y - startY) <= slop
}
