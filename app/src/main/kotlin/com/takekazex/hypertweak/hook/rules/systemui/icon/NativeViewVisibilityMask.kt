package com.takekazex.hypertweak.hook.rules.systemui.icon

import java.util.WeakHashMap

/** Retains the latest host request while a container-local replacement owns the view. */
internal class NativeViewVisibilityMask(private val hiddenVisibility: Int) {
    private val hostRequests = WeakHashMap<Any, Int>()

    fun hostRequest(view: Any, visibility: Int, masked: Boolean): Int {
        if (!masked) {
            hostRequests.remove(view)
            return visibility
        }
        hostRequests[view] = visibility
        return hiddenVisibility
    }

    fun reconcile(view: Any, visibility: Int, masked: Boolean): Int {
        if (!masked) return hostRequests.remove(view) ?: visibility
        if (!hostRequests.containsKey(view)) hostRequests[view] = visibility
        return hiddenVisibility
    }
}
