package com.takekazex.hypertweak.hook.rules.systemui.island

/**
 * One frame of the expanded-island pull-down. The host grows the fake view's outline downward
 * from its rest height and drives `alpha` from 1 to 0 as the drag nears the mini-window trigger;
 * both are animated back by Folme on release. The content stretches to the outline and blurs
 * with that progress, using the host's own 40px content-blur scale.
 */
internal data class IslandPullDownFrame(val scale: Float, val blurRadius: Int) {
    companion object {
        const val MAX_SCALE = 1.6f
        const val MAX_BLUR = 40
        private const val BLUR_STEP = 2
        private const val EPSILON = 0.001f

        /** Returns null at rest, which restores the native content. */
        fun of(restHeight: Int, outlineHeight: Int, alpha: Float, blurSupported: Boolean): IslandPullDownFrame? {
            if (restHeight <= 0 || outlineHeight <= 0 || alpha.isNaN()) return null
            val scale = (outlineHeight.toFloat() / restHeight).coerceIn(1f, MAX_SCALE)
            val progress = (1f - alpha).coerceIn(0f, 1f)
            if (scale <= 1f + EPSILON && progress <= EPSILON) return null
            // Quantized so the native blur is not rebuilt for every sub-pixel step.
            val blur = if (blurSupported) ((MAX_BLUR * progress).toInt() / BLUR_STEP) * BLUR_STEP else 0
            return IslandPullDownFrame(scale, blur)
        }
    }
}
