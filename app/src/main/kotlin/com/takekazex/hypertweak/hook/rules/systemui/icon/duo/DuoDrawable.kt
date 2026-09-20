package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.graphics.Picture
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.graphics.withSave
import com.takekazex.hypertweak.hook.rules.systemui.icon.MobileTypeLabelStyle
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Three-in-one glyph: a power ring, cellular/Wi-Fi state and the active SIM signal.
 *
 * Coordinates are authored in a 32-unit viewport and scaled uniformly into the bounds by the
 * smaller side, so the host's non-square battery slot cannot stretch the artwork.
 *
 * The compact cellular form uses a stacked or single signal in the ring and places its type in the
 * open bottom arc. Expanded Duo keeps the battery ring and original host battery glyph; native
 * network icons return to their expanded positions.
 *
 * Host foreground tint drives every layer; battery semantics stay on the charged part of the ring.
 */
class DuoDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 800, false)
        textAlign = Paint.Align.CENTER
    }
    private val networkSuffixPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val track = RectF()
    private val signalBounds = RectF()
    private val batteryBounds = Rect()
    private val signalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var opacity = 255
    private var dotsKey: Pair<Int, Boolean>? = null
    private var dotsPicture: Picture? = null
    private var signalFilterColor = Int.MIN_VALUE
    private var signalColorFilter: PorterDuffColorFilter? = null

    var foreground: Int = Color.WHITE
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var hidePowerTrack = false
    var hiddenSignalRows: Set<Int> = emptySet()
    var networkOnly = false
    var hideNetwork = false
    var batteryOnly = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var hideSignalDots = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var small5GaEnabled = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var content: DuoContent? = null
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var cellularSignalPicture: Picture? = null
        set(value) { if (field !== value) { field = value; invalidateSelf() } }
    var innerBatteryDrawable: Drawable? = null
        set(value) { if (field !== value) { field = value; invalidateSelf() } }

    override fun draw(canvas: Canvas) {
        val state = content ?: return
        if (bounds.isEmpty) return
        val side = min(bounds.width(), bounds.height()).toFloat()
        if (side <= 0f) return
        val cellular = !networkOnly && !batteryOnly && state.wifiLevel == null &&
            (state.networkLabel != null || state.noService)
        val compactRing = !networkOnly
        val save = canvas.save()
        try {
            canvas.translate(
                bounds.left + (bounds.width() - side) / 2f,
                bounds.top + (bounds.height() - side) / 2f
            )
            canvas.scale(side / VIEWPORT, side / VIEWPORT)

            canvas.withSave {
                if (compactRing) {
                    translate(CENTER, COMPACT_RING_CENTER_Y)
                    scale(COMPACT_RING_SCALE, COMPACT_RING_SCALE)
                    translate(-CENTER, -CENTER)
                }
                if (!networkOnly && !hidePowerTrack) drawPowerTrack(this, state)
                if (batteryOnly) {
                    drawInnerBattery(this)
                } else if (state.airplaneMode) {
                    if (!hideNetwork) drawAirplane(this)
                } else {
                    if (state.wifiLevel != null && !hideNetwork) {
                        drawWifi(this, state.wifiLevel)
                    } else if (cellular) {
                        drawCellularSignal(this, state)
                    } else if (networkOnly && !hideNetwork) {
                        drawNetworkLabel(this, state, CENTER)
                    }
                }
            }

            if (cellular && !hideNetwork && state.networkLabel != null) {
                drawNetworkLabel(canvas, state, LABEL_CENTER_Y)
            } else if (!cellular && !batteryOnly && !networkOnly &&
                state.wifiLevel != null && !hideSignalDots) {
                drawSignalDots(canvas, state)
            }
            if (!hideNetwork && !batteryOnly && state.noInternet) drawNoInternet(canvas, cellular)
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
     * Centre the type optically from the active font metrics. The compact cellular caller places it
     * in the open space below the smaller ring; the hand-off renderer centres it on the native type
     * target.
     */
    private fun drawNetworkLabel(canvas: Canvas, state: DuoContent, centerY: Float) {
        val label = state.networkLabel.orEmpty()
        if (label.isEmpty()) return
        paint.style = Paint.Style.FILL
        colorOf(foreground)
        paint.textSize = LABEL_TEXT_SIZE
        val fontHeight = paint.fontMetrics.let { it.descent - it.ascent }
        if (fontHeight > LABEL_MAX_HEIGHT) paint.textSize *= LABEL_MAX_HEIGHT / fontHeight
        val small5Ga = if (small5GaEnabled) {
            MobileTypeLabelStyle.small5GaParts(label)
        } else {
            null
        }
        if (small5Ga != null) {
            paint.textAlign = Paint.Align.LEFT
            val suffixPaint = networkSuffixPaint.apply {
                set(paint)
                textSize = paint.textSize * MobileTypeLabelStyle.SMALL_5GA_SUFFIX_SCALE
            }
            var baseWidth = paint.measureText(small5Ga.baseText)
            var suffixWidth = suffixPaint.measureText(small5Ga.suffixText)
            val width = baseWidth + suffixWidth
            if (width > LABEL_MAX_WIDTH && width > 0f) {
                val scale = LABEL_MAX_WIDTH / width
                paint.textSize *= scale
                suffixPaint.textSize *= scale
                baseWidth = paint.measureText(small5Ga.baseText)
                suffixWidth = suffixPaint.measureText(small5Ga.suffixText)
            }
            val metrics = paint.fontMetrics
            val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
            val left = CENTER - (baseWidth + suffixWidth) / 2f
            canvas.drawText(small5Ga.baseText, left, baseline, paint)
            canvas.drawText(small5Ga.suffixText, left + baseWidth, baseline, suffixPaint)
            paint.textAlign = Paint.Align.CENTER
            return
        }
        val measured = paint.measureText(label)
        if (measured > LABEL_MAX_WIDTH && measured > 0f) {
            paint.textSize *= LABEL_MAX_WIDTH / measured
        }
        val metrics = paint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, CENTER, baseline, paint)
    }

    fun signalDotsPicture(): Picture? {
        val state = content?.takeIf { it.wifiLevel != null } ?: return null
        val key = state.mobileLevel to state.noService
        if (dotsKey != key || dotsPicture == null) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            dotsPicture = Picture().apply {
                val canvas = beginRecording(VIEWPORT.toInt(), VIEWPORT.toInt())
                try {
                    for (index in 0 until SIGNAL_DOT_COUNT) {
                        dotPaint.alpha = if (!state.noService && index < state.mobileLevel) 255 else (255 * SIGNAL_DOT_RESERVE).toInt()
                        canvas.drawCircle(SIGNAL_DOT_X[index], SIGNAL_DOT_Y[index], SIGNAL_DOT_RADIUS, dotPaint)
                    }
                } finally { endRecording() }
            }
            dotsKey = key
        }
        return dotsPicture
    }

    fun signalDotsBounds(pictureCrop: RectF, localBounds: RectF): Boolean {
        if (bounds.isEmpty || content?.wifiLevel == null) return false
        pictureCrop.set(SIGNAL_DOT_X.first() - SIGNAL_DOT_RADIUS,
            SIGNAL_DOT_Y.minOrNull()!! - SIGNAL_DOT_RADIUS,
            SIGNAL_DOT_X.last() + SIGNAL_DOT_RADIUS, SIGNAL_DOT_Y.maxOrNull()!! + SIGNAL_DOT_RADIUS)
        val side = min(bounds.width(), bounds.height()).toFloat()
        val scale = side / VIEWPORT
        localBounds.set(pictureCrop.left * scale, pictureCrop.top * scale,
            pictureCrop.right * scale, pictureCrop.bottom * scale)
        localBounds.offset(bounds.left + (bounds.width() - side) / 2f, bounds.top + (bounds.height() - side) / 2f)
        return true
    }

    /** Matching crop in recorded-picture coordinates and this drawable's local view coordinates. */
    fun signalRowBounds(row: Int, pictureCrop: RectF, localBounds: RectF): Boolean {
        val commands = cellularSignalPicture ?: return false
        val rows = content?.cellularSignalLevels?.size ?: return false
        if (row !in 0 until rows || bounds.isEmpty) return false
        val top = if (rows > 1 && row == 1) .632f else 0f
        val bottom = if (rows > 1 && row == 0) .632f else 1f
        pictureCrop.set(0f, top * commands.height, commands.width.toFloat(), bottom * commands.height)
        val side = min(bounds.width(), bounds.height()).toFloat()
        val scale = side / VIEWPORT
        val left = bounds.left + (bounds.width() - side) / 2f
        val y = bounds.top + (bounds.height() - side) / 2f
        val signalTop = CELL_SIGNAL_CENTER_Y - CELL_SIGNAL_SIZE / 2f
        localBounds.set(
            left + (CENTER - CELL_SIGNAL_SIZE / 2f * COMPACT_RING_SCALE) * scale,
            y + (COMPACT_RING_CENTER_Y + (signalTop + CELL_SIGNAL_SIZE * top - CENTER) * COMPACT_RING_SCALE) * scale,
            left + (CENTER + CELL_SIGNAL_SIZE / 2f * COMPACT_RING_SCALE) * scale,
            y + (COMPACT_RING_CENTER_Y + (signalTop + CELL_SIGNAL_SIZE * bottom - CENTER) * COMPACT_RING_SCALE) * scale)
        return true
    }

    /** Reuse the module's single/stacked SVG when available; the compact fallback keeps previews useful. */
    private fun drawCellularSignal(canvas: Canvas, state: DuoContent) {
        val picture = cellularSignalPicture
        if (picture != null) {
            if (signalFilterColor != foreground) {
                signalFilterColor = foreground
                signalColorFilter = PorterDuffColorFilter(foreground, PorterDuff.Mode.SRC_IN)
            }
            signalPaint.colorFilter = signalColorFilter
            signalPaint.alpha = opacity
            signalBounds.set(CENTER - CELL_SIGNAL_SIZE / 2f, CELL_SIGNAL_CENTER_Y - CELL_SIGNAL_SIZE / 2f,
                CENTER + CELL_SIGNAL_SIZE / 2f, CELL_SIGNAL_CENTER_Y + CELL_SIGNAL_SIZE / 2f)
            val count = canvas.saveLayer(signalBounds, signalPaint)
            try {
                val rows = state.cellularSignalLevels.size.coerceAtMost(2)
                for (row in 0 until rows) {
                    if (row in hiddenSignalRows) continue
                    canvas.withSave {
                        if (rows == 2) {
                            val divider = signalBounds.top + signalBounds.height() * .632f
                            clipRect(signalBounds.left, if (row == 0) signalBounds.top else divider,
                                signalBounds.right, if (row == 0) divider else signalBounds.bottom)
                        }
                        drawPicture(picture, signalBounds)
                    }
                }
            } finally { canvas.restoreToCount(count) }
            return
        }

        val levels = state.cellularSignalLevels.take(2)
        when (levels.size) {
            0 -> Unit
            1 -> if (0 !in hiddenSignalRows) drawFallbackSignalRow(canvas, levels[0], 21.8f, SINGLE_SIGNAL_HEIGHTS)
            else -> {
                if (0 !in hiddenSignalRows) drawFallbackSignalRow(canvas, levels[0], 15.0f, STACKED_SIGNAL_TOP_HEIGHTS)
                if (1 !in hiddenSignalRows) drawFallbackSignalRow(canvas, levels[1], 23.0f, STACKED_SIGNAL_BOTTOM_HEIGHTS)
            }
        }
    }

    private fun drawFallbackSignalRow(canvas: Canvas, level: Int, bottom: Float, heights: FloatArray) {
        paint.style = Paint.Style.FILL
        for (index in 0 until SIGNAL_DOT_COUNT) {
            val lit = level >= 0 && index < level
            colorOf(foreground, if (lit) 1f else SIGNAL_DOT_RESERVE)
            val left = SIGNAL_BAR_LEFT + index * SIGNAL_BAR_PITCH
            signalBounds.set(left, bottom - heights[index], left + SIGNAL_BAR_WIDTH, bottom)
            canvas.drawRoundRect(signalBounds, SIGNAL_BAR_RADIUS, SIGNAL_BAR_RADIUS, paint)
        }
    }

    /** Draw the host's current solid/hollow battery glyph without taking its drawable ownership. */
    private fun drawInnerBattery(canvas: Canvas) {
        val drawable = innerBatteryDrawable
        if (drawable != null) {
            val intrinsicWidth = drawable.intrinsicWidth
            val intrinsicHeight = drawable.intrinsicHeight
            val ratio = if (intrinsicWidth > 0 && intrinsicHeight > 0)
                intrinsicWidth.toFloat() / intrinsicHeight else FALLBACK_BATTERY_RATIO
            val width = min(BATTERY_GLYPH_MAX_WIDTH, BATTERY_GLYPH_MAX_HEIGHT * ratio)
            val height = width / ratio
            val left = (CENTER - width / 2f).roundToInt()
            val top = (CENTER - height / 2f).roundToInt()
            batteryBounds.set(drawable.bounds)
            try {
                drawable.setBounds(left, top, (CENTER + width / 2f).roundToInt(),
                    (CENTER + height / 2f).roundToInt())
                drawable.alpha = opacity
                drawable.draw(canvas)
            } finally {
                drawable.bounds = batteryBounds
            }
            return
        }

        error("Host battery drawing is unavailable")
    }

    /** Four Wi-Fi-mode data-SIM levels; the two outer dots ride higher along the ring's lower arc. */
    private fun drawSignalDots(canvas: Canvas, state: DuoContent) {
        paint.style = Paint.Style.FILL
        for (index in 0 until SIGNAL_DOT_COUNT) {
            val lit = !state.noService && index < state.mobileLevel
            colorOf(foreground, if (lit) 1f else SIGNAL_DOT_RESERVE)
            canvas.drawCircle(SIGNAL_DOT_X[index], SIGNAL_DOT_Y[index], SIGNAL_DOT_RADIUS, paint)
        }
    }

    private fun drawNoInternet(canvas: Canvas, cellular: Boolean) {
        paint.style = Paint.Style.FILL
        colorOf(foreground)
        paint.textSize = NO_INTERNET_TEXT_SIZE
        canvas.drawText("!", if (cellular) CELLULAR_NO_INTERNET_X else 23.6f,
            if (cellular) CELLULAR_NO_INTERNET_Y else 13.8f, paint)
    }

    private fun colorOf(value: Int, fraction: Float = 1f) {
        paint.color = value
        paint.alpha = (Color.alpha(value) * opacity / 255f * fraction.coerceIn(0f, 1f))
            .toInt().coerceIn(0, 255)
    }

    override fun setAlpha(alpha: Int) {
        val next = alpha.coerceIn(0, 255)
        if (opacity != next) { opacity = next; invalidateSelf() }
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

        // Keep the cellular label in the compact glyph's lower opening. The metric-derived baseline
        // keeps it optically aligned even when MIUI swaps the concrete typeface implementation.
        const val LABEL_TEXT_SIZE = 10.5f
        const val LABEL_MAX_WIDTH = 17.0f
        const val LABEL_MAX_HEIGHT = 10.2f
        /** The type sits in the lower opening; the signal remains centred in the ring. */
        const val LABEL_CENTER_Y = 25.0f

        const val COMPACT_RING_SCALE = 0.90f
        const val COMPACT_RING_CENTER_Y = 14.8f
        const val CELL_SIGNAL_SIZE = 18f
        const val CELL_SIGNAL_CENTER_Y = 15.0f
        const val CELLULAR_NO_INTERNET_X = 25.5f
        const val CELLULAR_NO_INTERNET_Y = 7.5f

        const val SIGNAL_BAR_LEFT = 8.1f
        const val SIGNAL_BAR_PITCH = 4.0f
        const val SIGNAL_BAR_WIDTH = 2.45f
        const val SIGNAL_BAR_RADIUS = 0.85f
        val SINGLE_SIGNAL_HEIGHTS = floatArrayOf(3.2f, 5.9f, 8.7f, 11.5f)
        val STACKED_SIGNAL_TOP_HEIGHTS = floatArrayOf(1.5f, 2.8f, 4.5f, 7.4f)
        val STACKED_SIGNAL_BOTTOM_HEIGHTS = floatArrayOf(1.7f, 1.7f, 1.7f, 1.7f)

        const val BATTERY_GLYPH_MAX_WIDTH = 20.0f
        const val BATTERY_GLYPH_MAX_HEIGHT = 12.0f
        const val BATTERY_GLYPH_STROKE = 1.15f
        const val BATTERY_GLYPH_RADIUS = 1.7f
        const val BATTERY_TERMINAL_WIDTH = 1.4f
        const val BATTERY_TERMINAL_HEIGHT = 3.2f
        const val FALLBACK_BATTERY_RATIO = 1.8f

        const val NO_INTERNET_TEXT_SIZE = 6.2f

        const val SIGNAL_DOT_COUNT = 4
        const val SIGNAL_DOT_RADIUS = 1.35f
        const val SIGNAL_DOT_RESERVE = 0.28f
        val SIGNAL_DOT_X: FloatArray get() = DuoDotGeometry.x
        val SIGNAL_DOT_Y: FloatArray get() = DuoDotGeometry.y
    }
}
