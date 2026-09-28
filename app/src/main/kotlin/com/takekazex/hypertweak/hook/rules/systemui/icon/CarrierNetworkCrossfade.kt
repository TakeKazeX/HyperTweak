package com.takekazex.hypertweak.hook.rules.systemui.icon

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.widget.ImageView

/** Keeps the outgoing glyph painted until a replacement is ready, then fades both in one slot. */
internal class CarrierNetworkCrossfade(
    private val cellular: ImageView,
    private val wifi: ImageView,
    private val onSlotVisible: (Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val leased = HashSet<ImageView>()
    private var framePosted = false
    private var startedAt = 0L
    private var startCellular = 0f
    private var startWifi = 0f
    private var cellularAlpha = 0f
    private var wifiAlpha = 0f
    private var targetCellular = 0f
    private var targetWifi = 0f
    private var mode = CarrierNetworkMode.EMPTY
    private var pendingEmpty: Runnable? = null

    private val frame = Choreographer.FrameCallback {
        framePosted = false
        val progress = ((SystemClock.uptimeMillis() - startedAt).toFloat() / FADE_MS)
            .coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        cellularAlpha = startCellular + (targetCellular - startCellular) * eased
        wifiAlpha = startWifi + (targetWifi - startWifi) * eased
        applyAlphas()
        if (progress < 1f) postFrame()
        else if (mode == CarrierNetworkMode.EMPTY) onSlotVisible(false)
    }

    fun select(next: CarrierNetworkMode) {
        if (next == CarrierNetworkMode.EMPTY && mode != CarrierNetworkMode.EMPTY) {
            // The two host flows can briefly publish neither network while a route changes.
            // Keep the outgoing bitmap until its replacement can actually enter the slot.
            if (pendingEmpty == null) {
                pendingEmpty = Runnable {
                    pendingEmpty = null
                    transitionTo(CarrierNetworkMode.EMPTY)
                }.also { main.postDelayed(it, EMPTY_GRACE_MS) }
            }
            return
        }
        pendingEmpty?.let(main::removeCallbacks)
        pendingEmpty = null
        if (next == mode) return
        transitionTo(next)
    }

    private fun transitionTo(next: CarrierNetworkMode) {
        val firstPaint = mode == CarrierNetworkMode.EMPTY && !framePosted
        cancelFrame()
        mode = next
        if (next != CarrierNetworkMode.EMPTY) onSlotVisible(true)
        targetCellular = if (next == CarrierNetworkMode.CELLULAR || next == CarrierNetworkMode.BOTH) 1f else 0f
        targetWifi = if (next == CarrierNetworkMode.WIFI || next == CarrierNetworkMode.BOTH) 1f else 0f
        if (firstPaint) {
            cellularAlpha = targetCellular
            wifiAlpha = targetWifi
            applyAlphas()
            return
        }
        startCellular = cellularAlpha
        startWifi = wifiAlpha
        startedAt = SystemClock.uptimeMillis()
        postFrame()
    }

    private fun postFrame() {
        if (framePosted) return
        framePosted = true
        choreographer.postFrameCallback(frame)
    }

    private fun cancelFrame() {
        if (framePosted) choreographer.removeFrameCallback(frame)
        framePosted = false
    }

    fun applyAlphas() {
        if (cellular !in leased) cellular.alpha = cellularAlpha
        if (wifi !in leased) wifi.alpha = wifiAlpha
    }

    /** A shade or Duo handoff owns this child alpha until it releases its measured target. */
    fun lease(view: ImageView, active: Boolean) {
        if (active) leased += view else {
            leased -= view
            applyAlphas()
        }
    }

    fun clear() {
        pendingEmpty?.let(main::removeCallbacks)
        pendingEmpty = null
        cancelFrame()
        leased.clear()
        mode = CarrierNetworkMode.EMPTY
        cellularAlpha = 0f
        wifiAlpha = 0f
        applyAlphas()
        onSlotVisible(false)
    }

    private companion object {
        const val FADE_MS = 260f
        const val EMPTY_GRACE_MS = 900L
    }
}

internal enum class CarrierNetworkMode { EMPTY, CELLULAR, WIFI, BOTH }

/** A connected access point is insufficient: Wi-Fi takes this slot only as default data route. */
internal object CarrierNetworkChoice {
    fun select(cellular: Boolean, wifiDefaultAndRendered: Boolean, keepCellularType: Boolean): CarrierNetworkMode = when {
        wifiDefaultAndRendered && cellular && keepCellularType -> CarrierNetworkMode.BOTH
        wifiDefaultAndRendered -> CarrierNetworkMode.WIFI
        cellular -> CarrierNetworkMode.CELLULAR
        else -> CarrierNetworkMode.EMPTY
    }

    /** Reserve the complete position before Wi-Fi starts connecting, then never shrink mid-row. */
    fun requiredWidth(
        minimum: Int,
        cellularWidth: Int,
        wifiWidth: Int,
        keepBoth: Boolean,
        gap: Int,
        previousWidth: Int
    ): Int {
        val needed = if (keepBoth && cellularWidth > 0) {
            cellularWidth.toLong() + maxOf(minimum, wifiWidth).coerceAtLeast(0) + gap.coerceAtLeast(0)
        } else {
            maxOf(minimum, cellularWidth, wifiWidth).toLong()
        }
        return maxOf(previousWidth, needed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}
