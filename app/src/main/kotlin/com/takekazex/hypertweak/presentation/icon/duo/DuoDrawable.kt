package com.takekazex.hypertweak.presentation.icon.duo

import android.content.Context
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
import com.takekazex.hypertweak.settings.contract.MobileTypeLabelStyle
import com.takekazex.hypertweak.presentation.icon.AirplaneGlyph
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
enum class DuoRenderLayer { COMPOSITE, POWER_TRACK }

class DuoDrawable(context: Context, private val layer: DuoRenderLayer = DuoRenderLayer.COMPOSITE) : Drawable() {
    private val airplaneDrawable = if (layer == DuoRenderLayer.POWER_TRACK) null else AirplaneGlyph.loadGlyph(context)
    var showTopPercent = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var topIndicatorsEnabled = true
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    /** Power handover keeps its arc but draws the moving header independently. */
    var hideTopIndicators = false
    var topGapProgress = 0f
    var cellularDots = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var dualSignalDots = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var hiddenDotRows: Set<Int> = emptySet()
    var mirrorAirplane = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 800, false)
        textAlign = Paint.Align.CENTER
    }
    private val headerRenderer = DuoTopRenderer()
    private val networkSuffixPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val track = RectF()
    private val signalBounds = RectF()
    private val batteryBounds = Rect()
    private val signalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var opacity = 255
    private var dotsKey: DotKey? = null
    private var dotsPicture: Picture? = null
    private var signalFilterColor = Int.MIN_VALUE
    private var signalColorFilter: PorterDuffColorFilter? = null

    var nativeWifi: Drawable? = null
        set(value) { if (field !== value) { field = value; invalidateSelf() } }

    var sizes = DuoSizes()
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    var foreground: Int = Color.WHITE
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var hidePowerTrack = false
    var hiddenSignalRows: Set<Int> = emptySet()
    var networkOnly = false
    var hideAirplane = false
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
            state.hasCellularSignal
        val compactRing = !networkOnly
        val top = if (!networkOnly && !batteryOnly && !hidePowerTrack && topIndicatorsEnabled)
            headerRenderer.measure(state.battery.percent, showTopPercent, state.battery.charging, sizes)
        else null
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
                if (!networkOnly && !hidePowerTrack) {
                    drawPowerTrack(this, state, top?.geometry)
                    if (top != null && !hideTopIndicators) headerRenderer.draw(this, top, foreground, opacity)
                }
                if (layer == DuoRenderLayer.POWER_TRACK) return@withSave
                if (batteryOnly) {
                    drawInnerBattery(this)
                } else if (state.airplaneMode && state.wifiLevel == null) {
                    if (networkOnly && !hideAirplane && !hideNetwork) drawAirplane(this)
                } else {
                    if (state.wifiLevel != null && !hideNetwork) {
                        drawWifi(this, state.wifiLevel)
                    } else if (cellular && !cellularDots) {
                        drawCellularSignal(this, state)
                    } else if (networkOnly && !hideNetwork) {
                        drawNetworkLabel(this, state, CENTER)
                    }
                }
            }

            if (layer == DuoRenderLayer.POWER_TRACK) return
            if (cellular && cellularDots && !hideNetwork) drawSignalDots(canvas)
            if (state.airplaneMode && !batteryOnly && !networkOnly) {
                if (!hideAirplane) canvas.withSave {
                    translate(0f, LABEL_CENTER_Y - CENTER)
                    scale(.58f, .58f, CENTER, CENTER)
                    drawAirplane(this)
                }
            } else if (cellular && !hideNetwork && state.networkLabel != null) {
                drawNetworkLabel(canvas, state, LABEL_CENTER_Y)
            } else if (!cellular && !batteryOnly && !networkOnly &&
                state.wifiLevel != null && !hideSignalDots) {
                drawSignalDots(canvas)
            }
        } finally {
            canvas.restoreToCount(save)
        }
    }

    /** Reserve ring first, then the charged portion of the same arc on top. */
    private fun drawPowerTrack(canvas: Canvas, state: DuoContent, top: DuoTopGeometry.Header?) = canvas.withSave {
        scale(sizes.ring, sizes.ring, CENTER, CENTER)
        drawPowerTrackUnscaled(this, state, top)
    }

    private fun drawPowerTrackUnscaled(canvas: Canvas, state: DuoContent, top: DuoTopGeometry.Header?) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = TRACK_STROKE
        track.set(CENTER - TRACK_RADIUS, CENTER - TRACK_RADIUS, CENTER + TRACK_RADIUS, CENTER + TRACK_RADIUS)
        colorOf(foreground, TRACK_RESERVE)
        drawTrackArcs(canvas, 1f, top)
        val percent = state.battery.percent.coerceIn(0, 100)
        if (percent == 0) return
        colorOf(when (state.battery.tone) {
            BatteryTone.PERFORMANCE -> state.battery.performanceColor ?: foreground
            BatteryTone.NORMAL -> foreground
            BatteryTone.CHARGING -> Color.rgb(48, 209, 88)
            BatteryTone.LOW -> Color.rgb(255, 69, 58)
            BatteryTone.POWER_SAVE -> Color.rgb(255, 204, 0)
        })
        drawTrackArcs(canvas, percent / 100f, top)
    }

    private fun drawTrackArcs(canvas: Canvas, fraction: Float, top: DuoTopGeometry.Header?) {
        if (top == null || topGapProgress >= 1f) {
            canvas.drawArc(track, TRACK_START, TRACK_SWEEP * fraction, false, paint)
            return
        }
        val halfGap = top.halfGapDegrees * (1f - topGapProgress.coerceIn(0f, 1f))
        val halfSweep = 270f - halfGap - TRACK_START
        val sweep = halfSweep * 2f * fraction
        canvas.drawArc(track, TRACK_START, minOf(sweep, halfSweep), false, paint)
        if (sweep > halfSweep) {
            canvas.drawArc(track, 270f + halfGap, sweep - halfSweep, false, paint)
        }
    }

    /** Header-only drawing in the authored viewport, for the finger-driven percentage path. */
    fun drawTopIndicators(canvas: Canvas) {
        val state = content ?: return
        val top = headerRenderer.measure(state.battery.percent, showTopPercent, state.battery.charging, sizes) ?: return
        canvas.withSave {
            translate(CENTER, COMPACT_RING_CENTER_Y)
            scale(COMPACT_RING_SCALE, COMPACT_RING_SCALE)
            translate(-CENTER, -CENTER)
            headerRenderer.draw(this, top, foreground, opacity)
        }
    }

    fun topIndicatorViewportBounds(out: RectF): Boolean {
        val state = content ?: return false
        val top = headerRenderer.measure(state.battery.percent, showTopPercent, state.battery.charging, sizes)?.geometry
            ?: return false
        val cy = COMPACT_RING_CENTER_Y + (top.centerY - CENTER) * COMPACT_RING_SCALE
        val halfWidth = top.width * COMPACT_RING_SCALE / 2f
        val halfHeight = top.height * COMPACT_RING_SCALE / 2f
        out.set(CENTER - halfWidth, cy - halfHeight, CENTER + halfWidth, cy + halfHeight)
        return true
    }

    fun signalDotRows(): List<DuoSignalRow> {
        val state = content
        if (cellularDots && state != null && state.wifiLevel == null && !state.airplaneMode) {
            return state.cellularSignalRows.take(2).ifEmpty {
                state.cellularSignalLevels.take(2).mapIndexed { i, level -> DuoSignalRow(-1 - i, i, level) }
            }
        }
        return DuoPolicy.dotRows(state, dualSignalDots)
    }
    private val centerDots get() = cellularDots && content?.wifiLevel == null
    private fun centralDotScale() = COMPACT_RING_SCALE * sizes.cellular


    private fun dotX(index: Int, rows: Int): Float = if (centerDots)
        CENTER + (index - 1.5f) * DUAL_DOT_COLUMN_PITCH * centralDotScale()
        else if (rows == 1) SIGNAL_DOT_X[index] else CENTER + (index - 1.5f) * DUAL_DOT_COLUMN_PITCH
    private fun dotY(index: Int, row: Int, rows: Int): Float = if (centerDots)
        COMPACT_RING_CENTER_Y + (CELL_SIGNAL_CENTER_Y - CENTER) * COMPACT_RING_SCALE +
            (row - (rows - 1) / 2f) * DUAL_DOT_ROW_PITCH * centralDotScale()
        else if (rows == 1) SIGNAL_DOT_Y[index] else DUAL_DOT_TOP + row * DUAL_DOT_ROW_PITCH
    private fun dotRadius(rows: Int): Float = (if (centerDots) DUAL_DOT_RADIUS * centralDotScale()
        else if (rows == 1) SIGNAL_DOT_RADIUS else DUAL_DOT_RADIUS) *
        sizes.dots.coerceAtMost(if (rows == 1 && !centerDots) 1.5f else 1.2f)

    /**
     * Wi-Fi strength climbs from the centre mark outward: level 0 keeps the reserve outline,
     * 1 lights the centre mark, 2 adds the inner arc, and 3 or more adds the outer arc.
     */
    private fun drawWifi(canvas: Canvas, rawLevel: Int) = canvas.withSave {
        scale(sizes.wifi, sizes.wifi, CENTER, CENTER)
        drawWifiUnscaled(this, rawLevel)
    }

    private fun drawWifiUnscaled(canvas: Canvas, rawLevel: Int) {
        val native = nativeWifi as? NativeWifiDrawable
        if (native?.isReady() == true) {
            val half = NATIVE_WIFI_SIZE / 2f
            native.setBounds((CENTER - half).toInt(), (CENTER - half).toInt(),
                (CENTER + half).toInt(), (CENTER + half).toInt())
            native.alpha = opacity
            native.foreground = foreground
            native.draw(canvas)
            return
        }
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

    /** Draw the host vector with its native aspect ratio; mirror around the ring centre. */
    private fun drawAirplane(canvas: Canvas) = canvas.withSave {
        val glyph = airplaneDrawable ?: return@withSave
        scale(if (mirrorAirplane) -sizes.airplane else sizes.airplane, sizes.airplane, CENTER, CENTER)
        val width = glyph.intrinsicWidth.coerceAtLeast(1).toFloat()
        val height = glyph.intrinsicHeight.coerceAtLeast(1).toFloat()
        val factor = 21f / maxOf(width, height)
        translate(CENTER - width * factor / 2f, CENTER - height * factor / 2f)
        scale(factor, factor)
        glyph.setBounds(0, 0, width.toInt(), height.toInt())
        glyph.setTint(foreground)
        glyph.alpha = opacity
        glyph.draw(this)
    }

    /**
     * Type text stands in for the Wi-Fi layer while the default network is cellular.
     *
     * Centre the type optically from the active font metrics. The compact cellular caller places it
     * in the open space below the smaller ring; the hand-off renderer centres it on the native type
     * target.
     */
    private fun drawNetworkLabel(canvas: Canvas, state: DuoContent, centerY: Float) = canvas.withSave {
        scale(sizes.type, sizes.type, CENTER, centerY)
        drawNetworkLabelUnscaled(this, state, centerY)
    }

    private fun drawNetworkLabelUnscaled(canvas: Canvas, state: DuoContent, centerY: Float) {
        val label = state.networkLabel.orEmpty()
        if (label.isEmpty()) return
        paint.textAlign = Paint.Align.CENTER
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
        val rows = signalDotRows()
        if (rows.isEmpty()) return null
        val key = DotKey(rows, sizes.dots, centerDots, sizes.cellular)
        if (dotsKey != key || dotsPicture == null) {
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            dotsPicture = Picture().apply {
                val canvas = beginRecording(VIEWPORT.toInt(), VIEWPORT.toInt())
                try {
                    rows.forEachIndexed { rowIndex, row ->
                        for (index in 0 until SIGNAL_DOT_COUNT) {
                            dotPaint.alpha = if (index < row.level) 255 else (255 * SIGNAL_DOT_RESERVE).toInt()
                            canvas.drawCircle(dotX(index, rows.size), dotY(index, rowIndex, rows.size),
                                dotRadius(rows.size), dotPaint)
                        }
                    }
                } finally { endRecording() }
            }
            dotsKey = key
        }
        return dotsPicture
    }

    private data class DotKey(val rows: List<DuoSignalRow>, val dots: Float, val center: Boolean, val cellular: Float)
    private var morphBars: DuoSignalBars? = null
    private var morphKey: DotKey? = null
    private var dotMorphs: List<DuoDotMorph> = emptyList()

    fun signalDotMorph(row: Int, bars: DuoSignalBars): DuoDotMorph? {
        val rows = signalDotRows()
        if (row !in rows.indices) return null
        val key = DotKey(rows, sizes.dots, centerDots, sizes.cellular)
        if (morphBars !== bars || morphKey != key) {
            morphBars = bars
            morphKey = key
            dotMorphs = rows.mapIndexed { rowIndex, signal ->
                DuoDotMorph((0 until SIGNAL_DOT_COUNT).map { index ->
                    DuoMorphDot(dotX(index, rows.size), dotY(index, rowIndex, rows.size), dotRadius(rows.size),
                        if (index < signal.level) 1f else SIGNAL_DOT_RESERVE)
                }, bars, signal.level)
            }
        }
        return dotMorphs[row]
    }

    fun signalDotsBounds(pictureCrop: RectF, localBounds: RectF, row: Int = 0): Boolean {
        val rows = signalDotRows().size
        if (bounds.isEmpty || row !in 0 until rows) return false
        val radius = dotRadius(rows)
        val ys = (0 until SIGNAL_DOT_COUNT).map { dotY(it, row, rows) }
        pictureCrop.set(dotX(0, rows) - radius, ys.min() - radius,
            dotX(SIGNAL_DOT_COUNT - 1, rows) + radius, ys.max() + radius)
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
        val signalSize = CELL_SIGNAL_SIZE * sizes.cellular
        val signalTop = CELL_SIGNAL_CENTER_Y - signalSize / 2f
        localBounds.set(
            left + (CENTER - signalSize / 2f * COMPACT_RING_SCALE) * scale,
            y + (COMPACT_RING_CENTER_Y + (signalTop + signalSize * top - CENTER) * COMPACT_RING_SCALE) * scale,
            left + (CENTER + signalSize / 2f * COMPACT_RING_SCALE) * scale,
            y + (COMPACT_RING_CENTER_Y + (signalTop + signalSize * bottom - CENTER) * COMPACT_RING_SCALE) * scale)
        return true
    }

    /** Reuse the module's single/stacked SVG when available; the compact fallback keeps previews useful. */
    private fun drawCellularSignal(canvas: Canvas, state: DuoContent) = canvas.withSave {
        scale(sizes.cellular, sizes.cellular, CENTER, CELL_SIGNAL_CENTER_Y)
        drawCellularSignalUnscaled(this, state)
    }

    private fun drawCellularSignalUnscaled(canvas: Canvas, state: DuoContent) {
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
    private fun drawInnerBattery(canvas: Canvas) = canvas.withSave {
        scale(sizes.battery, sizes.battery, CENTER, CENTER)
        drawInnerBatteryUnscaled(this)
    }

    private fun drawInnerBatteryUnscaled(canvas: Canvas) {
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

    /** The same per-SIM geometry feeds center cellular dots and lower Wi-Fi signal dots. */
    private fun drawSignalDots(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        val rows = signalDotRows()
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex !in hiddenDotRows) for (index in 0 until SIGNAL_DOT_COUNT) {
                colorOf(foreground, if (index < row.level) 1f else SIGNAL_DOT_RESERVE)
                canvas.drawCircle(dotX(index, rows.size), dotY(index, rowIndex, rows.size), dotRadius(rows.size), paint)
            }
        }
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
        const val TRACK_STROKE = 2.45f
        const val DUAL_DOT_COLUMN_PITCH = 3.6f
        const val DUAL_DOT_ROW_PITCH = 3.1f
        const val DUAL_DOT_TOP = 22.7f
        const val DUAL_DOT_RADIUS = 1.1f
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
        const val NATIVE_WIFI_SIZE = 16f
        const val LABEL_CENTER_Y = 25.0f

        const val COMPACT_RING_SCALE = 0.90f
        const val COMPACT_RING_CENTER_Y = 14.8f
        const val CELL_SIGNAL_SIZE = 18f
        const val CELL_SIGNAL_CENTER_Y = 15.0f

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

        const val SIGNAL_DOT_COUNT = 4
        const val SIGNAL_DOT_RADIUS = 1.35f
        const val SIGNAL_DOT_RESERVE = 0.28f
        val SIGNAL_DOT_X: FloatArray get() = DuoDotGeometry.x
        val SIGNAL_DOT_Y: FloatArray get() = DuoDotGeometry.y
    }
}
