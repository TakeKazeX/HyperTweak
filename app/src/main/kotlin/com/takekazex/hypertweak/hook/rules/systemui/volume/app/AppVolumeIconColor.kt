package com.takekazex.hypertweak.hook.rules.systemui.volume.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap

/** Extracts a representative accent from the app icon on the playback worker. */
internal object AppVolumeIconColor {
    private const val SIZE = 32
    private const val HUES = 24
    private const val TONAL_BINS = 16
    private const val MIN_ACCENT_WEIGHT = 1f

    data class Sample(val accent: Int?, val tone: Int?)

    fun sample(icon: Drawable): Sample {
        val bitmap = createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val previousBounds = Rect(icon.bounds)
        try {
            icon.setBounds(0, 0, SIZE, SIZE)
            icon.draw(Canvas(bitmap))
            val pixels = IntArray(SIZE * SIZE)
            bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            val weights = FloatArray(HUES)
            val accents = IntArray(HUES)
            val best = FloatArray(HUES)
            val toneWeights = FloatArray(TONAL_BINS)
            val tones = IntArray(TONAL_BINS)
            val hsv = FloatArray(3)
            for (pixel in pixels) {
                val alpha = Color.alpha(pixel)
                if (alpha < 128) continue
                Color.colorToHSV(pixel, hsv)
                val opacity = alpha / 255f
                val opaque = pixel or Color.BLACK
                val tone = (hsv[2] * (TONAL_BINS - 1)).toInt()
                toneWeights[tone] += opacity
                tones[tone] = opaque
                if (hsv[1] < 0.08f || hsv[2] < 0.12f) continue
                val hue = ((hsv[0] + 7.5f) / 15f).toInt() % HUES
                val weight = hsv[1] * hsv[2] * opacity
                weights[hue] += weight
                if (weight > best[hue]) {
                    best[hue] = weight
                    accents[hue] = opaque
                }
            }
            val hue = weights.indices.maxByOrNull { weights[it] } ?: 0
            val tone = toneWeights.indices.maxByOrNull { toneWeights[it] } ?: 0
            return Sample(
                accent = accents[hue].takeIf { weights[hue] >= MIN_ACCENT_WEIGHT },
                tone = tones[tone].takeIf { toneWeights[tone] > 0f },
            )
        } finally {
            icon.bounds = previousBounds
            bitmap.recycle()
        }
    }
}
