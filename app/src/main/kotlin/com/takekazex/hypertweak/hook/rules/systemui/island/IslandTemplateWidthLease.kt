package com.takekazex.hypertweak.hook.rules.systemui.island

import android.view.View
import android.util.Log
import android.view.ViewTreeObserver
import android.widget.LinearLayout

/** Keep native area animations, but remove weighted redistribution and control the physical camera seat. */
internal class IslandTemplateWidthLease {
    private class Entry(val parent: LinearLayout, val left: View, val right: View, var cutoutWidth: Int) {
        private val leftParams = left.layoutParams as LinearLayout.LayoutParams
        private val rightParams = right.layoutParams as LinearLayout.LayoutParams
        private val leftWeight = leftParams.weight
        private val rightWeight = rightParams.weight
        private val middle = (parent.indexOfChild(left) + 1 until parent.indexOfChild(right)).map {
            val child = parent.getChildAt(it)
            Triple(child, child.layoutParams, child.layoutParams.width)
        }
        private val observer = parent.viewTreeObserver
        private val listener = ViewTreeObserver.OnPreDrawListener {
            // Native template-factory animation callbacks can rewrite the cutout after acquisition.
            // Repair the actual LayoutParams before a frame, then let Android measure once more.
            runCatching { !apply() }.getOrElse { failure ->
                Log.e("HyperTweak", "island template pre-draw repair failed", failure)
                runCatching { release() }
                true
            }
        }
        init { observer.addOnPreDrawListener(listener); apply() }

        fun apply(): Boolean {
            var changed = false
            fun zeroWeight(view: View, params: LinearLayout.LayoutParams) {
                if (view.layoutParams === params && params.weight != 0f) {
                    params.weight = 0f
                    view.layoutParams = params
                    changed = true
                }
            }
            zeroWeight(left, leftParams)
            zeroWeight(right, rightParams)
            middle.forEach { (view, params, _) ->
                if (view.layoutParams === params && params.width != cutoutWidth) {
                    params.width = cutoutWidth
                    view.layoutParams = params
                    changed = true
                }
            }
            if (changed) IslandLayoutInvalidation.request(parent)
            return changed
        }

        fun release(nativeCutoutWidth: Int? = null) {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            if (left.layoutParams === leftParams && leftParams.weight == 0f) {
                leftParams.weight = leftWeight
                left.layoutParams = leftParams
            }
            if (right.layoutParams === rightParams && rightParams.weight == 0f) {
                rightParams.weight = rightWeight
                right.layoutParams = rightParams
            }
            middle.forEach { (view, params, width) ->
                if (view.layoutParams === params && (params.width == cutoutWidth || nativeCutoutWidth != null)) {
                    params.width = nativeCutoutWidth ?: width
                    view.layoutParams = params
                }
            }
            IslandLayoutInvalidation.request(parent)
        }
    }
    private val entries = LinkedHashMap<Any, MutableList<Entry>>()

    fun acquire(owner: Any, left: View, right: View, cutoutWidth: Int = 0) {
        val parent = left.parent as? LinearLayout ?: return
        if (right.parent !== parent || parent.indexOfChild(left) >= parent.indexOfChild(right) ||
            left.layoutParams !is LinearLayout.LayoutParams || right.layoutParams !is LinearLayout.LayoutParams) return
        val owned = entries.getOrPut(owner) { mutableListOf() }
        val entry = owned.firstOrNull { it.parent === parent }
        if (entry != null) { entry.cutoutWidth = cutoutWidth; entry.apply() }
        else owned.add(Entry(parent, left, right, cutoutWidth))
    }

    /** A replacement hook generation may inherit a zero camera seat without owning its lease. */
    fun restoreCutout(left: View, right: View, nativeCutoutWidth: Int) {
        val parent = left.parent as? LinearLayout ?: return
        val leftIndex = parent.indexOfChild(left)
        if (nativeCutoutWidth < 0 || right.parent !== parent || parent.indexOfChild(right) != leftIndex + 2) return
        val cutout = parent.getChildAt(leftIndex + 1)
        val params = cutout.layoutParams
        if (params.width != nativeCutoutWidth) {
            params.width = nativeCutoutWidth
            cutout.layoutParams = params
            IslandLayoutInvalidation.request(parent)
        }
    }

    fun release(owner: Any, nativeCutoutWidth: Int? = null) {
        require(nativeCutoutWidth == null || nativeCutoutWidth >= 0)
        entries.remove(owner)?.forEach { it.release(nativeCutoutWidth) }
    }
    fun releaseAll(nativeCutoutWidth: (Any) -> Int? = { null }) {
        entries.keys.toList().forEach { release(it, nativeCutoutWidth(it)) }
    }
}
