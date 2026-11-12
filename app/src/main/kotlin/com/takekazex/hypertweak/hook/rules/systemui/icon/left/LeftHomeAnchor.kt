package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.takekazex.hypertweak.util.platform.ResourceLookup

/** Locates the host row without owning or changing any views. */
internal class LeftHomeAnchor(private val name: (View?) -> String, private val reject: (String) -> Unit) {
    class HomeAnchor(val host: ViewGroup, val after: View?, val description: String)

    /**
     * Finds where the home left icons go: right after the clock, in the nearest horizontal row above
     * it ([LeftAnchorPolicy]). When the clock is missing or sits in a row the icons cannot join
     * (a ROM that centers or right-aligns it), the icons start the stock left container instead.
     */
    fun resolveHomeAnchor(root: ViewGroup, right: ViewGroup): HomeAnchor? {
        fun find(name: String): View? = ResourceLookup.identifier(root.resources, name, "id", "com.android.systemui")
            .takeIf { it != 0 }?.let { root.findViewById(it) }
        val clock = find("clock")?.takeIf { !it.isInside(right) }
        if (clock != null) {
            val chain = ArrayList<ViewGroup>()
            var parent = clock.parent as? ViewGroup
            while (parent != null) {
                chain += parent
                if (parent === root) break
                parent = parent.parent as? ViewGroup
            }
            val levels = chain.map { LeftAnchorPolicy.Level(isHorizontalRow(it), right.isInside(it)) }
            LeftAnchorPolicy.hostLevel(levels)?.let { index ->
                val after = if (index == 0) clock else chain[index - 1]
                return HomeAnchor(chain[index], after, if (index == 0) "after=clock" else "after=${name(after)}")
            }
        }
        val stock = find("phone_status_bar_left_container") as? ViewGroup
        if (stock != null && isHorizontalRow(stock) && !right.isInside(stock)) {
            return HomeAnchor(stock, null, "after=start clockParent=${name(clock?.parent as? View)}")
        }
        reject("no horizontal row for left icons clockParent=${name(clock?.parent as? View)} " +
            "stock=${name(stock)}")
        return null
    }

    private fun isHorizontalRow(view: ViewGroup): Boolean =
        view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL

    /** True when this view is [ancestor] or one of its descendants. */
    private fun View.isInside(ancestor: ViewGroup): Boolean {
        var current: View? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

}
