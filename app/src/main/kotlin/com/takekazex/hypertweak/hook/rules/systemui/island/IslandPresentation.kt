package com.takekazex.hypertweak.hook.rules.systemui.island

/** Render-only projection. Expanded, hidden and deleted states always keep host semantics. */
internal object IslandPresentation {
    fun <T> isSummary(view: T, frames: Set<T>, summaries: List<T>, expanded: T?): Boolean =
        view in frames && view !== expanded && view in summaries

    fun <T> project(state: T, full: Boolean, isSmall: (T) -> Boolean, large: () -> T): T =
        if (full && isSmall(state)) large() else state

    fun contentTranslation(screenWidth: Int, clipStart: Float, clipEnd: Float, nativeTranslation: Float, alpha: Float): Float =
        if (alpha > 0f) nativeTranslation + (clipStart + clipEnd - screenWidth) / 2f else 0f
}
