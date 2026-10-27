package com.takekazex.hypertweak.hook.rules.systemui.island.template

import android.view.View
import android.widget.LinearLayout

/** Keep native area animations, but remove weighted redistribution and control the physical camera seat. */
internal class IslandTemplateWidthLease {
    private class Entry(val parent: LinearLayout, val left: View, val right: View, var cutoutWidth: Int, var media: Boolean) {
        private var leftInnerPadding = left.paddingEnd
        private var rightInnerPadding = right.paddingStart
        private var paddingOwned = false
        private val leftParams = left.layoutParams as LinearLayout.LayoutParams
        private val rightParams = right.layoutParams as LinearLayout.LayoutParams
        private val leftWeight = leftParams.weight
        private val rightWeight = rightParams.weight
        private val middle = (parent.indexOfChild(left) + 1 until parent.indexOfChild(right)).map {
            val child = parent.getChildAt(it)
            Triple(child, child.layoutParams, child.layoutParams.width)
        }
        private val cutoutToken = Any()
        init { apply() }

        fun update(cutoutWidth: Int, media: Boolean) {
            if (media && !this.media) {
                leftInnerPadding = left.paddingEnd
                rightInnerPadding = right.paddingStart
            }
            this.cutoutWidth = cutoutWidth
            this.media = media
            apply()
        }

        fun apply(): Boolean {
            IslandTemplateCutout.acquire(parent, cutoutToken, cutoutWidth)
            var changed = false
            fun zeroWeight(view: View, params: LinearLayout.LayoutParams) {
                if (view.layoutParams === params && params.weight != 0f) {
                    params.weight = 0f
                    view.layoutParams = params
                    changed = true
                }
            }
            if (media || paddingOwned) {
                val leftPadding = if (media) 0 else leftInnerPadding
                val rightPadding = if (media) 0 else rightInnerPadding
                if (left.paddingEnd != leftPadding) {
                    left.setPaddingRelative(left.paddingStart, left.paddingTop, leftPadding, left.paddingBottom)
                    changed = true
                }
                if (right.paddingStart != rightPadding) {
                    right.setPaddingRelative(rightPadding, right.paddingTop, right.paddingEnd, right.paddingBottom)
                    changed = true
                }
                paddingOwned = media
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

        fun releaseCameraSeat() {
            if (media) IslandCameraSeat.release(parent)
        }

        fun release(nativeCutoutWidth: Int? = null) {
            IslandTemplateCutout.release(parent, cutoutToken)
            if (media && left.paddingEnd == 0)
                left.setPaddingRelative(left.paddingStart, left.paddingTop, leftInnerPadding, left.paddingBottom)
            if (media && right.paddingStart == 0)
                right.setPaddingRelative(rightInnerPadding, right.paddingTop, right.paddingEnd, right.paddingBottom)
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

    /** A content refresh may replace one real/fake pair while the other is still live. */
    fun retain(owner: Any, areas: List<Pair<View, View>>) {
        val owned = entries[owner] ?: return
        val iterator = owned.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (areas.any { (left, right) ->
                    left === entry.left && right === entry.right &&
                        left.parent === entry.parent && right.parent === entry.parent
                }) continue
            entry.releaseCameraSeat()
            entry.release()
            iterator.remove()
        }
        if (owned.isEmpty()) entries.remove(owner)
    }

    fun acquire(owner: Any, left: View, right: View, cutoutWidth: Int = 0) =
        acquire(owner, left, right, cutoutWidth, false)

    /** A side media pair has no physical camera between its original native areas. */
    fun acquireMedia(owner: Any, left: View, right: View) = acquire(owner, left, right, 0, true)

    private fun acquire(owner: Any, left: View, right: View, cutoutWidth: Int, media: Boolean) {
        val parent = left.parent as? LinearLayout ?: return
        if (right.parent !== parent || parent.indexOfChild(left) >= parent.indexOfChild(right) ||
            left.layoutParams !is LinearLayout.LayoutParams || right.layoutParams !is LinearLayout.LayoutParams) return
        val owned = entries.getOrPut(owner) { mutableListOf() }
        val entry = owned.firstOrNull { it.parent === parent }
        if (entry != null) {
            val wasMedia = entry.media
            entry.update(cutoutWidth, media)
            if (media && !wasMedia) IslandCameraSeat.acquire(parent)
            else if (!media && wasMedia) IslandCameraSeat.release(parent)
        } else {
            owned.add(Entry(parent, left, right, cutoutWidth, media))
            if (media) IslandCameraSeat.acquire(parent)
        }
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
        entries.remove(owner)?.forEach {
            it.releaseCameraSeat()
            it.release(nativeCutoutWidth)
        }
    }
    fun releaseAll(nativeCutoutWidth: (Any) -> Int? = { null }) {
        entries.keys.toList().forEach { release(it, nativeCutoutWidth(it)) }
    }
}
