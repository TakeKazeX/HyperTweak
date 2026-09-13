package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

internal object DuoPrivacyGeometry {
    fun isTransition(state: String?): Boolean = when (state) {
        "START_SHOW_PRIVACY", "COMPLETE_SHOW_PRIVACY", "START_HOME_TO_DOT", "START_HIDE_PRIVACY" -> true
        else -> false
    }

    fun offset(left: Float, right: Float, chipLeft: Float, chipRight: Float, gap: Float, rtl: Boolean): Float {
        if (listOf(left, right, chipLeft, chipRight, gap).any { !it.isFinite() } || chipRight <= chipLeft) return 0f
        return if (rtl) (chipRight + gap - left).coerceAtLeast(0f)
        else (chipLeft - gap - right).coerceAtMost(0f)
    }
}
