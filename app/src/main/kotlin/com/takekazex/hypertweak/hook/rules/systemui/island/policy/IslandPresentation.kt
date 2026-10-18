package com.takekazex.hypertweak.hook.rules.systemui.island.policy

import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.FullIslandFrame

/** Render-only projection. Expanded, hidden and deleted states always keep host semantics. */
internal object IslandPresentation {
    fun <T> isSummary(view: T, frames: Set<T>, summaries: List<T>, expanded: T?): Boolean =
        view in frames && view !== expanded && view in summaries

    fun <T> project(state: T, full: Boolean, isSmall: (T) -> Boolean, large: () -> T): T =
        if (full && isSmall(state)) large() else state

    /** A neighbour changing seats does not invalidate an unchanged occupied full template. */
    fun needsPublication(before: FullIslandFrame?, after: FullIslandFrame?, geometryChanged: Boolean, force: Boolean): Boolean =
        force || if (before != null && after != null) before != after else geometryChanged

    fun keepBorrowedSummary(before: FullIslandFrame?, after: FullIslandFrame?, eventTarget: Boolean, force: Boolean): Boolean =
        !eventTarget && !force && before?.full == true && after == before

    fun contentTranslation(screenWidth: Int, clipStart: Float, clipEnd: Float, nativeTranslation: Float, alpha: Float): Float =
        if (alpha > 0f) nativeTranslation + (clipStart + clipEnd - screenWidth) / 2f else 0f
}
