package com.takekazex.hypertweak.dock

import com.takekazex.hypertweak.settings.contract.DockBounds

internal data class DockPose(val x: Float, val y: Float, val scale: Float) {
    /** Transform the inner viewport View around (0, 0), retaining the material layout offsets. */
    fun inViewport(bounds: DockBounds) = DockPose(x - bounds.x * scale, y - bounds.y * scale, scale)

    companion object {
        /** Native nesting: row gesture -> Hotseat UnlockWidget -> desktop root controller. */
        fun resolve(width: Int, height: Int, density: Float, bounds: DockBounds, motion: DockMotion?): DockPose {
            val rowScale = motion?.scale?.toFloat() ?: 1f
            val rootScale = motion?.screenScale?.toFloat() ?: 1f
            val unlockScale = motion?.unlockScale?.toFloat() ?: 1f
            val centerX = width / 2f
            val rowPivotY = height - (49 + 68) * density + (motion?.originY?.toFloat() ?: 0f) * density
            var x = centerX + (bounds.x - centerX) * rowScale + (motion?.x?.toFloat() ?: 0f) * density
            var y = rowPivotY + (bounds.y - rowPivotY) * rowScale + (motion?.y?.toFloat() ?: 0f) * density
            // UnlockWidget uses Alignment.center on the native 68dp row, 49dp above the bottom.
            val unlockPivotY = height - (49 + 68 / 2f) * density
            x = centerX + (x - centerX) * unlockScale + (motion?.unlockX?.toFloat() ?: 0f) * density
            y = unlockPivotY + (y - unlockPivotY) * unlockScale + (motion?.unlockY?.toFloat() ?: 0f) * density
            return DockPose(centerX + (x - centerX) * rootScale,
                height / 2f + (y - height / 2f) * rootScale, rowScale * unlockScale * rootScale)
        }
    }
}
