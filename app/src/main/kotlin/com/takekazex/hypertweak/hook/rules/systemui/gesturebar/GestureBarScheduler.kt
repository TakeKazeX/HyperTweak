package com.takekazex.hypertweak.hook.rules.systemui.gesturebar

import android.os.Handler
import android.os.SystemClock

internal interface GestureBarScheduler {
    fun now(): Long
    fun post(task: Runnable, delay: Long = 0): Boolean
    fun remove(task: Runnable)
    fun contains(task: Runnable): Boolean
}

internal class HandlerGestureBarScheduler(private val handler: () -> Handler) : GestureBarScheduler {
    override fun now() = SystemClock.uptimeMillis()
    override fun post(task: Runnable, delay: Long) = handler().postDelayed(task, delay)
    override fun remove(task: Runnable) = handler().removeCallbacks(task)
    override fun contains(task: Runnable) = handler().hasCallbacks(task)
}
