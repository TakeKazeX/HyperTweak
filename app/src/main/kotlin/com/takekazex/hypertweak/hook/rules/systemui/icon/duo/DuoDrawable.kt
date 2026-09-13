package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * Three-in-one glyph: a power ring, a network step and four signal dots.
 *
 * Coordinates are authored in a 32-unit viewport and scaled uniformly into the bounds by the
 * smaller side, so the host's non-square battery slot cannot stretch the artwork.
 *
 * Proportions follow the reference artwork in `docs/IPHONE_DUO_SIGNAL/assets/reference`: the ring's
 * gap is centred at the bottom and is closed by the signal dots, Wi-Fi is two shallow arcs plus the
 * centre mark, and the four dots sit on one arc whose outer dots ride higher.
 *
 * Host foreground tint drives every layer; battery semantics stay on the charged part of the ring.
 */
class DuoDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val track = RectF()
    private var opacity = 255

    var foreground: Int = Color.WHITE
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var content: DuoContent? = null
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    override fun draw(canvas: Canvas) {
        val state = content ?: return
        if (bounds.isEmpty) return
        val side = min(bounds.width(), bounds.height()).toFloat()
        if (side <= 0f) return
        val save = canvas.save()
        try {
            canvas.translate(
                bounds.left + (bounds.width() - side) / 2f,
                bounds.top + (bounds.height() - side) / 2f
            )
            canvas.scale(side / VIEWPORT, side / VIEWPORT)
            drawPowerTrack(canvas, state)
            if (state.airplaneMode) {
                drawAirplane(canvas)
            } else {
                if (state.wifiLevel != null) drawWifi(canvas, state.wifiLevel) else drawNetworkLabel(canvas, state)
                drawSignalDots(canvas, state)
                if (state.noService) drawNoService(canvas)
                if (state.noInternet) drawNoInternet(canvas)
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    /** Reserve ring first, then the charged portion of the same arc on top. */
    private fun drawPowerTrack(canvas: Canvas, state: DuoContent) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = TRACK_STROKE
        track.set(CENTER - TRACK_RADIUS, CENTER - TRACK_RADIUS, CENTER + TRACK_RADIUS, CENTER + TRACK_RADIUS)
        colorOf(foreground, TRACK_RESERVE)
        canvas.drawArc(track, TRACK_START, TRACK_SWEEP, false, paint)
        val percent = state.battery.percent.coerceIn(0, 100)
        if (percent == 0) return
        colorOf(when (state.battery.tone) {
            BatteryTone.NORMAL -> foreground
            BatteryTone.CHARGING -> Color.rgb(48, 209, 88)
            BatteryTone.LOW -> Color.rgb(255, 69, 58)
            BatteryTone.POWER_SAVE -> Color.rgb(255, 204, 0)
        })
        canvas.drawArc(track, TRACK_START, TRACK_SWEEP * percent / 100f, false, paint)
    }

    /**
     * Wi-Fi strength climbs from the centre mark outward: level 0 keeps the reserve outline,
     * 1 lights the centre mark, 2 adds the inner arc, and 3 or more adds the outer arc.
     */
    private fun drawWifi(canvas: Canvas, rawLevel: Int) {
        val level = rawLevel.coerceAtLeast(0)
        wifiArc(
            canvas,
            WIFI_OUTER_START_X, WIFI_OUTER_END_X, WIFI_OUTER_END_Y, WIFI_OUTER_CONTROL_Y,
            WIFI_OUTER_STROKE, lit = level >= 3
        )
        wifiArc(
            canvas,
            WIFI_INNER_START_X, WIFI_INNER_END_X, WIFI_INNER_END_Y, WIFI_INNER_CONTROL_Y,
            WIFI_INNER_STROKE, lit = level >= 2
        )
        drawWifiCentreMark(canvas, lit = level >= 1)
    }

    /** One shallow arc: a quadratic with round caps reads closer to the artwork than a circle arc. */
    private fun wifiArc(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        endY: Float,
        controlY: Float,
        strokeWidth: Float,
        lit: Boolean
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        colorOf(foreground, if (lit) 1f else GLYPH_RESERVE)
        path.reset()
        path.moveTo(startX, endY)
        path.quadTo(CENTER, controlY, endX, endY)
        canvas.drawPath(path, paint)
    }

    /** Rounded kite under the arcs; the reference reads it as a small solid drop. */
    private fun drawWifiCentreMark(canvas: Canvas, lit: Boolean) {
        paint.style = Paint.Style.FILL
        colorOf(foreground, if (lit) 1f else GLYPH_RESERVE)
        path.reset()
        path.moveTo(CENTER - MARK_RADIUS, MARK_TOP_Y + MARK_RADIUS * 0.30f)
        path.quadTo(CENTER, MARK_TOP_Y - MARK_RADIUS * 0.30f, CENTER + MARK_RADIUS, MARK_TOP_Y + MARK_RADIUS * 0.30f)
        path.quadTo(CENTER + MARK_RADIUS * 0.95f, MARK_TOP_Y + MARK_RADIUS * 0.95f, CENTER + MARK_RADIUS * 0.42f, MARK_BOTTOM_Y - MARK_RADIUS * 0.25f)
        path.quadTo(CENTER, MARK_BOTTOM_Y + MARK_RADIUS * 0.15f, CENTER - MARK_RADIUS * 0.42f, MARK_BOTTOM_Y - MARK_RADIUS * 0.25f)
        path.quadTo(CENTER - MARK_RADIUS * 0.95f, MARK_TOP_Y + MARK_RADIUS * 0.95f, CENTER - MARK_RADIUS, MARK_TOP_Y + MARK_RADIUS * 0.30f)
        path.close()
        canvas.drawPath(path, paint)
    }

    /**
     * Airplane-mode silhouette traced from the host's real airplane glyph: broad swept wings,
     * a compact tail and a rounded nose.  Keeping it as one contiguous path avoids the seams and
     * odd double-fin look of the previous three-piece approximation at status-bar scale.
     */
    private fun drawAirplane(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        colorOf(foreground)
        path.reset()

        path.moveTo(12.64f, 9.58f)     // upper wing tip
        path.lineTo(14.39f, 14.83f)    // upper wing root
        path.lineTo(10.30f, 15.12f)    // upper tail root
        path.lineTo(8.84f, 13.37f)     // tail upper tip
        path.lineTo(8.84f, 18.92f)     // tail lower tip
        path.lineTo(10.30f, 17.17f)    // lower tail root
        path.lineTo(14.10f, 17.17f)    // lower fuselage/wing junction
        path.lineTo(14.39f, 17.46f)
        path.lineTo(12.64f, 22.13f)    // lower wing tip
        path.lineTo(13.52f, 22.42f)
        path.lineTo(17.90f, 17.17f)    // lower wing root
        path.lineTo(22.28f, 17.17f)    // lower nose base
        path.quadTo(23.16f, 17.17f, 23.16f, 16.29f)
        path.quadTo(23.16f, 15.12f, 22.28f, 15.12f) // rounded nose
        path.lineTo(17.90f, 15.12f)    // upper wing root
        path.lineTo(13.52f, 9.87f)
        path.close()

        canvas.drawPath(path, paint)
    }

    /**
     * Type text stands in for the Wi-Fi layer while the default network is cellular.
     *
     * Keep it optically centred in the ring from the active font metrics instead of pinning a
     * baseline. The host may substitute MiSans/Roboto variants, whose ascent/descent differ enough
     * that a fixed baseline makes short labels such as "5G" look noticeably low.
     */
    private fun drawNetworkLabel(canvas: Canvas, state: DuoContent) {
        val label = state.networkLabel.orEmpty()
        if (label.isEmpty()) return
        paint.style = Paint.Style.FILL
        colorOf(foreground)
        paint.textSize = LABEL_TEXT_SIZE
        val measured = paint.measureText(label)
        if (measured > LABEL_MAX_WIDTH && measured > 0f) {
            paint.textSize *= LABEL_MAX_WIDTH / measured
        }
        val metrics = paint.fontMetrics
        val baseline = LABEL_CENTER_Y - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, CENTER, baseline, paint)
    }

    /** Four signal levels; the two outer dots ride higher, so the row reads as an arc. */
    private fun drawSignalDots(canvas: Canvas, state: DuoContent) {
        paint.style = Paint.Style.FILL
        for (index in 0 until SIGNAL_DOT_COUNT) {
            val lit = !state.noService && index < state.mobileLevel
            colorOf(foreground, if (lit) 1f else SIGNAL_DOT_RESERVE)
            canvas.drawCircle(SIGNAL_DOT_X[index], SIGNAL_DOT_Y[index], SIGNAL_DOT_RADIUS, paint)
        }
    }

    private fun drawNoService(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = NO_SERVICE_STROKE
        colorOf(foreground)
        canvas.drawLine(9.8f, 22.6f, 22.2f, 17.2f, paint)
    }

    private fun drawNoInternet(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        colorOf(foreground)
        paint.textSize = NO_INTERNET_TEXT_SIZE
        canvas.drawText("!", 23.6f, 13.8f, paint)
    }

    private fun colorOf(value: Int, fraction: Float = 1f) {
        paint.color = value
        paint.alpha = (Color.alpha(value) * opacity / 255f * fraction.coerceIn(0f, 1f))
            .toInt().coerceIn(0, 255)
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    // Semantic battery colours are deliberately exempt from a whole-icon colour filter.
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    internal companion object {
        /** Authored square; [draw] scales it onto the bounds by the smaller side. */
        const val VIEWPORT = 32f
        const val CENTER = 16f

        /** Ring: outer diameter 31.2u. Rounded caps leave an apparent ~110-degree bottom gap. */
        const val TRACK_RADIUS = 14.6f
        const val TRACK_STROKE = 2.20f
        const val TRACK_START = 149f
        const val TRACK_SWEEP = 242f
        const val TRACK_RESERVE = 0.20f

        const val GLYPH_RESERVE = 0.30f

        /** Wi-Fi arcs are pixel-calibrated to the reference; the outer one is wider and thicker. */
        const val WIFI_OUTER_START_X = 10.2f
        const val WIFI_OUTER_END_X = 21.8f
        const val WIFI_OUTER_END_Y = 14.1f
        const val WIFI_OUTER_CONTROL_Y = 9.7f
        const val WIFI_OUTER_STROKE = 2.10f

        const val WIFI_INNER_START_X = 12.65f
        const val WIFI_INNER_END_X = 19.35f
        const val WIFI_INNER_END_Y = 16.65f
        const val WIFI_INNER_CONTROL_Y = 14.2f
        const val WIFI_INNER_STROKE = 1.95f

        const val MARK_TOP_Y = 18.2f
        const val MARK_BOTTOM_Y = 21.05f
        const val MARK_RADIUS = 1.82f

        // Cellular labels should occupy roughly the same visual weight as the Wi-Fi glyph.
        // 8.5u makes "5G" ~11u wide on the host's medium sans font; the metric-derived baseline
        // keeps it centred even when MIUI swaps the concrete typeface implementation.
        const val LABEL_TEXT_SIZE = 8.5f
        const val LABEL_MAX_WIDTH = 12.0f
        const val LABEL_CENTER_Y = 15.9f

        const val NO_SERVICE_STROKE = 1.50f
        const val NO_INTERNET_TEXT_SIZE = 6.2f

        const val SIGNAL_DOT_COUNT = 4
        const val SIGNAL_DOT_RADIUS = 1.55f
        const val SIGNAL_DOT_RESERVE = 0.28f
        val SIGNAL_DOT_X = floatArrayOf(8.40f, 13.25f, 18.75f, 23.60f)
        val SIGNAL_DOT_Y = floatArrayOf(28.32f, 30.33f, 30.33f, 28.32f)
    }
}
