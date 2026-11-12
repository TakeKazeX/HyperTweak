package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import android.graphics.Picture
import android.view.View
import com.takekazex.hypertweak.hook.rules.systemui.icon.GlyphEndpoint
import com.takekazex.hypertweak.presentation.icon.duo.DuoDrawable
import kotlin.math.roundToInt

/**
 * The airplane Duo draws below its ring, as a hand-over endpoint. The picture uses the geometry
 * [DuoPanelMotion] gives the same glyph; opacity is binary, as the ring either draws it or not.
 */
internal class DuoAirplaneEndpoint(private val binding: DuoBinding) : GlyphEndpoint {
    private val glyph = DuoDrawable(binding.battery.context).apply { networkOnly = true }
    private var recorded: Picture? = null
    private var recordedKey: List<Any?>? = null

    override val view: DuoIconView get() = binding.view

    override var value: Float
        get() = if (binding.view.airplaneYielded) 0f else 1f
        set(next) { binding.view.airplaneYielded = next < .5f }

    override fun picture(): Picture? {
        val view = binding.view
        val content = view.icon.content?.takeIf { it.airplaneMode } ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val side = view.drawnIconSidePx().toFloat()
        val top = ((view.height - side.toInt()) / 2).toFloat()
        val left = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) view.paddingLeft.toFloat()
            else view.width - view.paddingRight - side
        val box = side * .58f * view.icon.sizes.airplane
        val cx = left + side / 2f
        val cy = top + side * DuoDrawable.LABEL_CENTER_Y / DuoDrawable.VIEWPORT
        val rendered = content.copy(wifiLevel = null)
        val key = listOf(view.width, view.height, cx, cy, box, rendered, view.icon.foreground, view.icon.mirrorAirplane)
        recorded?.takeIf { recordedKey == key }?.let { return it }
        glyph.content = rendered
        glyph.foreground = view.icon.foreground
        glyph.mirrorAirplane = view.icon.mirrorAirplane
        glyph.alpha = 255
        glyph.setBounds((cx - box / 2f).roundToInt(), (cy - box / 2f).roundToInt(),
            (cx + box / 2f).roundToInt(), (cy + box / 2f).roundToInt())
        val picture = Picture()
        try {
            glyph.draw(picture.beginRecording(view.width, view.height))
        } finally {
            picture.endRecording()
        }
        recorded = picture
        recordedKey = key
        return picture
    }
}
