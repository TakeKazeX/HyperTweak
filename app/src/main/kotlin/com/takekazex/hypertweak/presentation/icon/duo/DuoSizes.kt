package com.takekazex.hypertweak.presentation.icon.duo

/** Component ratios are independent of the outer slot size; defaults preserve existing artwork. */
data class DuoSizes(
    val ring: Float = 1f,
    val wifi: Float = 1f,
    val cellular: Float = 1f,
    val type: Float = 1f,
    val dots: Float = 1f,
    val airplane: Float = 1f,
    val battery: Float = 1f,
    val percent: Float = 1f
) {
    companion object {
        fun ratio(percent: Int): Float = percent.coerceIn(50, 150) / 100f

        /** Component-wise interpolation, so a size change grows each part instead of jumping. */
        fun lerp(from: DuoSizes, to: DuoSizes, fraction: Float): DuoSizes {
            val t = fraction.coerceIn(0f, 1f)
            fun mix(a: Float, b: Float) = a + (b - a) * t
            return DuoSizes(
                mix(from.ring, to.ring), mix(from.wifi, to.wifi), mix(from.cellular, to.cellular),
                mix(from.type, to.type), mix(from.dots, to.dots), mix(from.airplane, to.airplane),
                mix(from.battery, to.battery), mix(from.percent, to.percent)
            )
        }
    }
}
