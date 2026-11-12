package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.takekazex.hypertweak.settings.contract.IconSlotPolicy

internal class LeftState(
        val leftHost: ViewGroup,
        /** The host child the container follows in [leftHost]; null starts the row. */
        val anchor: View?,
        val rightContainer: ViewGroup,
        val manager: Any,
        val isKeyguard: Boolean = false,
        /**
         * The status-bar view that owns this row's dependency object (`mDependence`), which is where
         * the island and shade controllers hang. Not [leftHost]: that is a plain container.
         */
        val barRoot: View? = null
    ) : KeyguardAirplaneScene {
        var presentation = LeftVisibilityPolicy.Result.RELEASE
        var leftContainer: LinearLayout? = null
        /** slot -> clone view (our own, sized from the system view of the same slot). */
        val clones = HashMap<String, View>()
        /** Slots with a valid clone; only these may be hidden in the right cluster. */
        var migratedSlots: Set<String> = emptySet()
        /** Last logged migration outcome, so the log reports changes only. */
        var migrationReport: String? = null
        var pendingMigrationReport: String? = null
        /** Original keyguard clock/alarm placement, used to restore the host tree exactly. */
        val anchorEntries = ArrayList<AnchorEntry>()
        var anchorWrapper: LinearLayout? = null
        var islandShowing: Boolean = false
        var islandHandler: Any? = null
        /** The lockscreen carrier layout sharing [leftHost] with the anchors; keyguard only. */
        override var keyguardCarrier: View? = null
        override val airplaneClone: View? get() = clones[IconSlotPolicy.SLOT_AIRPLANE]
        override val airplaneContainer: ViewGroup get() = rightContainer
        /** Module-owned crossfade between [keyguardCarrier] and [leftContainer]. */
        var carrierSwap: LeftCarrierSwapState? = null
        /** The airplane outlives an empty/disabled left container (Duo can own the destination). */
        val keyguardAirplane = LeftKeyguardAirplaneState()
    }

internal data class AnchorEntry(
        val view: View,
        val parent: ViewGroup,
        val index: Int,
        val layoutParams: ViewGroup.LayoutParams?
    )
