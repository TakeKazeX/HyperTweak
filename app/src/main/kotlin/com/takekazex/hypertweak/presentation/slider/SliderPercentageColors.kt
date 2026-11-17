package com.takekazex.hypertweak.presentation.slider

/** ARGB colours of the percentage labels, shared by the hook and the settings preview. */
object SliderPercentageColors {
    /** Flat popup capsule fill, its hairline stroke, and the text on every popup capsule. */
    data class Popup(val background: Int, val stroke: Int, val text: Int)

    // The host's collapsed button colour (`miui_volume_bg_color_collapse`) is dark grey in both
    // themes, so the popup uses its own light and dark pair.
    private val POPUP_LIGHT = Popup(0xF2FFFFFF.toInt(), 0x14000000, 0xCC000000.toInt())
    private val POPUP_DARK = Popup(0xF22C2C2E.toInt(), 0x1FFFFFFF, 0xE6FFFFFF.toInt())

    fun popup(dark: Boolean): Popup = if (dark) POPUP_DARK else POPUP_LIGHT

    /** Control-center `toggle_slider_top_text_color`: drawn for the slider's white fill. */
    const val CC_ON_FILL = 0x7F000000
    /** Control-center label above the fill, over the dimmed track. */
    const val CC_ON_TRACK = 0xE6FFFFFF.toInt()

    /** Host slider fill (`toggle_slider_progress_color`) and track, for the preview. */
    const val CC_FILL = 0xEBFFFFFF.toInt()
    const val CC_TRACK = 0xFF48484A.toInt()
}
