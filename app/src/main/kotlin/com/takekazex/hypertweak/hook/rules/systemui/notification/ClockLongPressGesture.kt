package com.takekazex.hypertweak.hook.rules.systemui.notification

import kotlin.math.abs

/** Recognition only: the window retains ownership of every touch event. */
internal class ClockLongPressGesture(
    private val downX: Float,
    private val downY: Float,
    private val slop: Float
) {
    var pending = true
        private set

    fun move(x: Float, y: Float) {
        if (abs(x - downX) > slop || abs(y - downY) > slop) pending = false
    }

    fun fire(): Boolean {
        val recognized = pending
        pending = false
        return recognized
    }
}
