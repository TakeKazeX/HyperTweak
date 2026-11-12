package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.os.Handler
import android.os.Looper

/**
 * Main-thread confinement for the icon state hubs.
 *
 * Unlike `HostAccess.onMain`, nothing here waits for the main thread: a host callback that
 * arrives on a binder or Flow thread must never stall on SystemUI's looper.
 */
internal object IconMainThread {
    private val handler = Handler(Looper.getMainLooper())

    val isCurrent: Boolean get() = Looper.myLooper() == handler.looper

    /** Runs [action] now when already on the main thread, otherwise queues it. */
    fun run(action: () -> Unit) {
        if (isCurrent) action() else handler.post(action)
    }

    /** Always queues [action], so it runs after the caller's current pass. */
    fun post(action: () -> Unit) {
        handler.post(action)
    }
}
