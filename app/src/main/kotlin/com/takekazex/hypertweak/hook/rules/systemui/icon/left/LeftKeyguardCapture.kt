package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.platform.ResourceLookup

/** Discovers and restores the native lockscreen anchors on the main thread. */
internal class LeftKeyguardCapture(private val host: Host) {
    interface Host {
        val retiring: Boolean
        val handler: Handler
        val states: MutableMap<Any, LeftState>
        fun teardown(state: LeftState)
        fun resolveIslandHandler(view: Any): Any?
        fun readIslandShowing(handler: Any?): Boolean
        fun reject(reason: String)
        fun viewName(view: View?): String
        fun detached(state: LeftState)
    }

    fun captureController(controller: Any) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            host.handler.post { if (!host.retiring) captureController(controller) }
            return
        }
        val view = field(controller, "mView") as? ViewGroup ?: return
        captureView(view)
    }

    fun captureView(view: ViewGroup) {
        if (view.javaClass.name != "com.android.systemui.statusbar.phone.MiuiKeyguardStatusBarView") return
        val manager = field(view, "mTintedIconManager") ?: return
        val right = field(manager, "mGroup") as? ViewGroup ?: return
        fun id(name: String) = ResourceLookup.identifier(view.resources, name, "id", "com.android.systemui")
        val leftFrame = view.findViewById<ViewGroup>(id("keyguard_left_frame")) ?: return
        val clock = view.findViewById<View>(id("keyguard_clock")) ?: return
        val alarm = view.findViewById<View>(id("ll_alarm_container")) ?: return
        val anchors = listOf(clock, alarm)
        synchronized(host.states) {
            val existing = host.states[manager]
            if (existing != null && existing.leftHost === leftFrame && existing.isKeyguard) return
            if (anchors.any { it.parent !== leftFrame }) {
                host.reject("lockscreen clock parent is ${host.viewName(clock.parent as? View)}, not keyguard_left_frame")
                return
            }
            existing?.let { host.teardown(it); host.states.remove(manager) }
            val state = LeftState(leftFrame, clock, right, manager, isKeyguard = true, barRoot = view)
            state.islandHandler = host.resolveIslandHandler(view)
            state.islandShowing = host.readIslandShowing(state.islandHandler)
            anchors.forEach { anchor ->
                val parent = anchor.parent as? ViewGroup ?: return@forEach
                state.anchorEntries += AnchorEntry(anchor, parent, parent.indexOfChild(anchor), copyLayoutParams(anchor.layoutParams))
            }
            if (state.anchorEntries.size == anchors.size) host.states[manager] = state
        }
    }

    fun detachController(controller: Any) {
        val view = field(controller, "mView") as? ViewGroup ?: return
        val manager = field(view, "mTintedIconManager") ?: return
        val state = synchronized(host.states) { host.states.remove(manager) } ?: return
        host.detached(state)
    }

    private fun field(instance: Any, name: String): Any? = HostAccess.field(instance.javaClass, name)?.get(instance)
    private fun copyLayoutParams(params: ViewGroup.LayoutParams?): ViewGroup.LayoutParams? = when (params) {
        is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(params)
        null -> null
        else -> ViewGroup.LayoutParams(params)
    }
}
