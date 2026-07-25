package com.takekazex.hypertweak.hook.rules.systemui

internal object GestureBarHitRegion {
    private const val FALLBACK_HANDLE_WIDTH_DP = 152f
    private const val HORIZONTAL_PADDING_DP = 12f
    private const val MAX_HIT_WIDTH_DP = 192f
    private const val HIT_HEIGHT_DP = 32f

    fun contains(
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        handleCenterX: Float?,
        handleWidth: Float?
    ): Boolean {
        if (!x.isFinite() || !y.isFinite() || screenWidth <= 0 || screenHeight <= 0 ||
            !density.isFinite() || density <= 0f
        ) {
            return false
        }

        val candidateWidth = handleWidth
            ?.takeIf { it.isFinite() && it > 0f }
            ?: FALLBACK_HANDLE_WIDTH_DP * density
        val hitWidth = (candidateWidth + HORIZONTAL_PADDING_DP * 2f * density)
            .coerceAtMost(MAX_HIT_WIDTH_DP * density)
            .coerceAtMost(screenWidth.toFloat())
        val halfWidth = hitWidth / 2f
        val centerX = (handleCenterX ?: screenWidth / 2f)
            .takeIf(Float::isFinite)
            ?.coerceIn(halfWidth, screenWidth - halfWidth)
            ?: screenWidth / 2f
        val top = (screenHeight - HIT_HEIGHT_DP * density).coerceAtLeast(0f)

        return x >= centerX - halfWidth &&
            x <= centerX + halfWidth &&
            y >= top &&
            y < screenHeight
    }
}
