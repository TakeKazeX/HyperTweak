package com.takekazex.hypertweak.presentation.icon

/** Immutable host motion, retained independently of every renderer generation. */
internal data class PanelState(
    val progress: Float = 0f,
    val visible: Boolean = false,
    val stretch: Float = 0f,
    val shadeSwitching: Boolean = false,
    val hostSwitching: Boolean = false,
    val shadeSwitchProgress: Float = 0f,
    val settledToControlCenter: Boolean = false,
    val keyguard: Boolean = false,
    val dozing: Boolean = false
) {
    fun expansion(value: Float): PanelState = if (!value.isFinite() || value !in 0f..1f || shadeSwitching) this
        else copy(progress = value, visible = visible || value > 0f,
            settledToControlCenter = settledToControlCenter && value >= 0.999f)
    fun visibility(value: Boolean): PanelState = if (shadeSwitching) this else
        copy(visible = value, progress = if (value) progress else 0f,
            stretch = if (value) stretch else 0f,
            settledToControlCenter = settledToControlCenter && value)
}
