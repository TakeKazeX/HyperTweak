package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Own the small tap area; replay the original DOWN before handing a drag to the native bar. */
internal class DuoTapGesture(private val view: View) {
    enum class Result { IGNORED, HANDLED, CLICK }
    var target: View? = null
        private set
    private var down: MotionEvent? = null
    private var dragging = false
    private val sourceLocation = IntArray(2)
    private val targetLocation = IntArray(2)

    fun setTarget(next: View?) {
        if (target === next) return
        cancel()
        target = next
    }

    fun cancel() {
        if (dragging) down?.let { event ->
            val cancel = MotionEvent.obtain(event)
            try { cancel.action = MotionEvent.ACTION_CANCEL; forward(cancel) } finally { cancel.recycle() }
        }
        down?.recycle()
        down = null
        dragging = false
        view.isPressed = false
    }

    fun onTouch(event: MotionEvent): Result {
        if (target == null) return Result.IGNORED
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            down = MotionEvent.obtain(event)
            view.isPressed = true
            return Result.HANDLED
        }
        val start = down ?: return Result.IGNORED
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        if (!dragging && (event.pointerCount > 1 ||
                !DuoTapPolicy.isTap(start.x, start.y, event.x, event.y, slop.toFloat(),
                    event.eventTime - start.eventTime, ViewConfiguration.getLongPressTimeout().toLong()))) {
            dragging = true
            view.isPressed = false
            forward(start)
        }
        if (dragging) forward(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val click = !dragging && event.actionMasked == MotionEvent.ACTION_UP
            down?.recycle()
            down = null
            dragging = false
            view.isPressed = false
            if (click) return Result.CLICK
        }
        return Result.HANDLED
    }

    private fun forward(event: MotionEvent) {
        val root = target ?: return
        if (!root.isAttachedToWindow) return
        view.getLocationOnScreen(sourceLocation)
        root.getLocationOnScreen(targetLocation)
        val copy = MotionEvent.obtain(event)
        try {
            copy.offsetLocation((sourceLocation[0] - targetLocation[0]).toFloat(),
                (sourceLocation[1] - targetLocation[1]).toFloat())
            runCatching { root.onTouchEvent(copy) }.onFailure {
                com.takekazex.hypertweak.util.logging.DebugLog.w("DuoSignal", "native drag dispatch failed", it)
            }
        } finally { copy.recycle() }
    }
}
