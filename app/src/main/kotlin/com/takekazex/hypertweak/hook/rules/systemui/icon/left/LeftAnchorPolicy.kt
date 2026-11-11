package com.takekazex.hypertweak.hook.rules.systemui.icon.left

/**
 * Chooses the row that hosts the home left icons, from the clock's ancestor chain.
 *
 * The icons are inserted into a horizontal row right after the clock, so the content that follows
 * (chips, notification icons) moves aside exactly as it does for the clock's own siblings. Custom
 * ROMs wrap or move the clock, so the row is found structurally instead of by one fixed parent:
 * the nearest horizontal row above the clock, provided that row does not also hold the right-hand
 * system icons. Inserting there would put the icons beside the right cluster instead of the clock.
 *
 * Host views are never reparented. A ROM can cast the clock's layout params to its own parent type,
 * and wrapping the clock would break that cast inside SystemUI.
 */
internal object LeftAnchorPolicy {
    /** One ancestor of the clock, nearest first. */
    data class Level(val horizontalRow: Boolean, val holdsSystemIcons: Boolean)

    /** Index into [levels] of the hosting row, or null when no ancestor qualifies. */
    fun hostLevel(levels: List<Level>): Int? {
        for ((index, level) in levels.withIndex()) {
            if (level.holdsSystemIcons) return null
            if (level.horizontalRow) return index
        }
        return null
    }
}
