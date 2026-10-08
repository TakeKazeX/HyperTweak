package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.graphics.Canvas
import android.view.View

/** Replay the bound status icon, including native tint/resource selection, without changing it. */
internal object PanelSourceGlyph {
    fun draw(canvas: Canvas, source: View, width: Float, height: Float, alpha: Int) {
        val save = canvas.save()
        try {
            canvas.scale(width / source.width, height / source.height)
            if (alpha < 255) {
                @Suppress("DEPRECATION")
                canvas.saveLayerAlpha(0f, 0f, source.width.toFloat(), source.height.toFloat(), alpha)
            }
            // View alpha belongs to the parent draw pass. The motion owns its own opacity while
            // the actual source child is alpha-masked to keep its layout slot and binder alive.
            source.draw(canvas)
        } finally {
            canvas.restoreToCount(save)
        }
    }
}
