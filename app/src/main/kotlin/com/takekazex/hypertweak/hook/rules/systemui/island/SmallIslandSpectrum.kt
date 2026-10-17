package com.takekazex.hypertweak.hook.rules.systemui.island

/** Extend only a side summary into unused space; never shrink or move the main island. */
internal object SmallIslandSpectrum {
    data class Seat(val left: Boolean, val frame: FullIslandFrame)

    /**
     * One native album/wave cell reserves its outer padding, icon and margins. The side-media
     * template lease removes the camera-facing inner padding, so admission excludes it too.
     */
    fun musicDemand(padding: Int, icon: Int, margin: Int, cameraPadding: Int): IslandContentSize? {
        if (padding < 0 || icon <= 0 || margin < 0 || cameraPadding < 0) return null
        val cell = padding.toLong() + icon + 2L * margin - cameraPadding
        if (cell <= 0 || cell > Int.MAX_VALUE) return null
        return IslandContentSize(cell.toInt(), cell.toInt())
    }

    /** Published on main, read by the native Binder position query without measuring Views. */
    data class ReturnGeometry(val key: String, val layout: ThreeIslandLayout, val content: IslandContentSize,
        val gap: Int, val clock: Int, val battery: Int, val safeLeft: Int, val safeRight: Int, val otherWidth: Int) {
        fun target(nativeX: Int, nativeWidth: Int): FullIslandFrame? {
            val left = when {
                nativeWidth <= 0 -> return null
                nativeX.toLong() + nativeWidth <= layout.bigX -> true
                nativeX.toLong() >= layout.bigX.toLong() + layout.bigWidth -> false
                else -> return null
            }
            return choose(layout, content, gap, clock, battery, safeLeft, safeRight, left, otherWidth)?.frame
        }
    }

    fun choose(layout: ThreeIslandLayout, content: IslandContentSize, gap: Int,
        clock: Int, battery: Int, safeLeft: Int, safeRight: Int,
        currentLeft: Boolean, otherWidth: Int): Seat? {
        val width = content.left.toLong() + content.right
        if (layout.full || layout.screenWidth <= 0 || layout.bigX < 0 || layout.bigWidth <= 0 ||
            content.left <= 0 || content.right <= 0 || width > Int.MAX_VALUE || gap < 0 ||
            clock < 0 || battery < 0 || otherWidth < 0) return null
        val leftRoom = layout.bigX.toLong() - gap - maxOf(clock, safeLeft, 0)
        val rightStart = layout.bigX.toLong() + layout.bigWidth + gap
        val rightRoom = layout.screenWidth.toLong() - maxOf(battery, safeRight, 0) - rightStart
        fun room(left: Boolean) = if (left) leftRoom else rightRoom
        // Scheduling owns the seat. Spectrum admission can only extend that same seat.
        if (room(currentLeft) < width || otherWidth > 0 && room(!currentLeft) < otherWidth) return null
        val left = currentLeft
        val x = if (left) layout.bigX.toLong() - gap - width else rightStart
        return Seat(left, FullIslandFrame(x.toInt(), content))
    }
}
