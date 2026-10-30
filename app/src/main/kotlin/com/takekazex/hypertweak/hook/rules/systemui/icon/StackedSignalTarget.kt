package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.view.View
import android.widget.ImageView
import com.takekazex.hypertweak.presentation.icon.duo.DuoSignalRow
import com.takekazex.hypertweak.presentation.icon.duo.SignalBarMorph

internal data class StackedSignalTarget(val view: ImageView, val rows: List<DuoSignalRow>,
    val split: Float, val contours: List<List<SignalBarMorph.Contour>>)

/** A measured child of a hidden holder is not a usable endpoint. Alpha may be leased by an overlay. */
internal fun signalEndpointVisible(view: View, container: View): Boolean {
    var node: View? = view
    while (node != null) {
        if (node.visibility != View.VISIBLE) return false
        if (node === container) return view.width > 0 && view.height > 0
        node = node.parent as? View
    }
    return false
}
