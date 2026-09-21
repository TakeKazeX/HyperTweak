package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

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
    }
}
