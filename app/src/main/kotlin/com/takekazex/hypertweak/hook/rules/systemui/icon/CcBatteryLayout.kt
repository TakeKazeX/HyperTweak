package com.takekazex.hypertweak.hook.rules.systemui.icon

/** Absolute horizontal span of one battery child, in the parent's coordinates. */
data class CcBatterySpan(val left: Int, val right: Int)

/**
 * Horizontal placement of the control-center battery's icon/percentage pair.
 *
 * OS4's `MiuiBatteryMeterView.onLayout` lays its children out by field, not by child order:
 * the icon container takes the leading edge and the percentage container is appended after it, so
 * the "outside" percentage always sits on the trailing side (visual right in LTR, visual left in
 * RTL). Moving the percentage to the leading side is therefore a re-layout, not a reorder, and the
 * arithmetic is kept here so it can be unit-tested without a device.
 */
object CcBatteryLayout {

    /** Leading edge of the pair, as `MiuiBatteryMeterView.onLayout` computes it for the icon. */
    fun leadingEdge(viewWidth: Int, paddingStart: Int, rtl: Boolean): Int =
        if (rtl) viewWidth - paddingStart else paddingStart

    /**
     * Spans for `(percentage, icon)` with the percentage on the leading side: `[%][icon]` in LTR
     * and `[icon][%]` in RTL. [leading] is [leadingEdge]; the pair keeps the host's own sizes, so
     * the parent's measured width does not change.
     */
    fun percentLeading(
        leading: Int,
        rtl: Boolean,
        iconWidth: Int,
        percentWidth: Int
    ): Pair<CcBatterySpan, CcBatterySpan> = if (rtl) {
        CcBatterySpan(leading - percentWidth, leading) to
            CcBatterySpan(leading - percentWidth - iconWidth, leading - percentWidth)
    } else {
        CcBatterySpan(leading, leading + percentWidth) to
            CcBatterySpan(leading + percentWidth, leading + percentWidth + iconWidth)
    }
}
