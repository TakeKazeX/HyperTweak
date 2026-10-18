package com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout

/** Natural native template widths, including its own edge padding but no camera placeholder. */
internal data class IslandContentSize(val left: Int, val right: Int) {
    val width: Int get() = left + right

    companion object {
        fun measured(left: Int, right: Int, height: Int): IslandContentSize? {
            if (left < 0 || right < 0 || height <= 0 || left.toLong() + right > Int.MAX_VALUE) return null
            val padding = (height - left - right).coerceAtLeast(0)
            return IslandContentSize(left + padding / 2, right + padding - padding / 2)
        }
    }
}

internal data class FullIslandFrame(val x: Int, val content: IslandContentSize, val full: Boolean = true) {
    val width: Int get() = content.width
}

/** Pack only occupied seats. Different templates never inherit each other's widths. */
internal object FullIslandRow {
    /** Keep the native pair; skip oversized queued tasks without starving later fitting tasks. */
    fun <T> admit(native: List<T>, candidates: List<T>, fits: (List<T>) -> Boolean): List<T> {
        var selected = native
        candidates.distinct().filterNot { it in native }.forEach { candidate ->
            val next = selected + candidate
            if (fits(next)) selected = next
        }
        return selected
    }

    fun calculate(screenWidth: Int, screenHeight: Int, clock: Int, battery: Int,
        contents: List<IslandContentSize>, gap: Int, safeLeft: Int = 0, safeRight: Int = 0): List<FullIslandFrame>? {
        if (screenWidth <= 0 || screenHeight <= 0 || clock < 0 || battery < 0 || gap < 0 ||
            contents.isEmpty() || contents.any { it.left < 0 || it.right < 0 || it.width <= 0 }) return null
        val left = maxOf(clock, safeLeft, 0)
        val right = maxOf(battery, safeRight, 0)
        val width = contents.sumOf { it.width.toLong() } + gap.toLong() * (contents.size - 1)
        val edge = maxOf(left, right)
        if (width > screenWidth.toLong() - 2L * edge) return null
        var x = ((screenWidth - width) / 2).toInt()
        return contents.map { content -> FullIslandFrame(x, content).also { x += content.width + gap } }
    }
}
