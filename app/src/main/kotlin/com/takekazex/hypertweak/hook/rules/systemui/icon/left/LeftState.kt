package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

internal class LeftState(
        val leftHost: ViewGroup,
        val clock: View,
        val rightContainer: ViewGroup,
        val manager: Any,
        val isKeyguard: Boolean = false,
        /**
         * The status-bar view that owns this row's dependency object (`mDependence`), which is where
         * the island and shade controllers hang. Not [leftHost]: that is a plain container.
         */
        val barRoot: View? = null
    ) {
        var presentation = LeftVisibilityPolicy.Result.RELEASE
        var leftContainer: LinearLayout? = null
        /** slot -> clone view (our own, sized from the system view of the same slot). */
        val clones = HashMap<String, View>()
        /** Slots with a valid clone; only these may be hidden in the right cluster. */
        var migratedSlots: Set<String> = emptySet()
        /** Original keyguard clock/alarm placement, used to restore the host tree exactly. */
        val anchorEntries = ArrayList<AnchorEntry>()
        var anchorWrapper: LinearLayout? = null
        var islandShowing: Boolean = false
        var islandHandler: Any? = null
    }

internal data class AnchorEntry(
        val view: View,
        val parent: ViewGroup,
        val index: Int,
        val layoutParams: ViewGroup.LayoutParams?
    )
