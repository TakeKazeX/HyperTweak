package com.takekazex.hypertweak.hook.rules.systemui

internal class GestureBarGestureDetector(
    moveSlop: Float,
    doubleTapSlop: Float,
    private val doubleTapTimeoutMs: Long
) {
    private val moveSlopSquared = moveSlop * moveSlop
    private val doubleTapSlopSquared = doubleTapSlop * doubleTapSlop

    private var active = false
    private var secondTap = false
    private var downX = 0f
    private var downY = 0f
    private var firstTapUpTime = NO_TIME
    private var firstTapX = 0f
    private var firstTapY = 0f

    fun onDown(eventTime: Long, x: Float, y: Float): Boolean {
        val elapsed = eventTime - firstTapUpTime
        secondTap = firstTapUpTime != NO_TIME &&
            elapsed in 0..doubleTapTimeoutMs &&
            distanceSquared(x, y, firstTapX, firstTapY) <= doubleTapSlopSquared

        if (secondTap || elapsed !in 0..doubleTapTimeoutMs) {
            clearTapHistory()
        }

        active = true
        downX = x
        downY = y
        return secondTap
    }

    fun onMove(x: Float, y: Float, pointerCount: Int): Boolean {
        if (!active) return false
        if (pointerCount > 1 || distanceSquared(x, y, downX, downY) > moveSlopSquared) {
            cancel()
            return false
        }
        return true
    }

    fun onUp(eventTime: Long, x: Float, y: Float): Boolean {
        if (!onMove(x, y, 1)) return false

        active = false
        if (secondTap) {
            secondTap = false
            clearTapHistory()
            return true
        }

        firstTapUpTime = eventTime
        firstTapX = x
        firstTapY = y
        return false
    }

    fun onLongPressTimeout(): Boolean {
        if (!active || secondTap) return false
        active = false
        clearTapHistory()
        return true
    }

    fun cancel() {
        active = false
        secondTap = false
        clearTapHistory()
    }

    fun clearTapHistory() {
        firstTapUpTime = NO_TIME
    }

    private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE
    }
}
