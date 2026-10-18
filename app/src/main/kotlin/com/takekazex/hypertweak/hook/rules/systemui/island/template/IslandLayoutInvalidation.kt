package com.takekazex.hypertweak.hook.rules.systemui.island.template

import android.view.View

/** Native premeasurement dirties children without invalidating cached ancestor measurements. */
internal object IslandLayoutInvalidation {
    fun request(view: View) {
        var current = view
        while (true) {
            current.forceLayout()
            val parent = current.parent as? View ?: break
            current = parent
        }
        // Every intermediate container is dirty before asking ViewRootImpl for a traversal.
        current.requestLayout()
        current.invalidate()
    }
}
