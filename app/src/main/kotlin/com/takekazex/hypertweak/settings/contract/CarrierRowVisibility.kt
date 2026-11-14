package com.takekazex.hypertweak.settings.contract

/** Whole control-center carrier rows, keyed by physical SIM slot rather than display order. */
internal object CarrierRowVisibility {
    const val NONE = 0
    const val SIM_ONE = 1
    const val SIM_TWO = 2
    const val BOTH = 3
    val RANGE = NONE..BOTH

    fun nativeControlsEnabled(hideDate: Boolean, twoLine: Boolean): Boolean = !(hideDate && twoLine)

    fun normalize(value: Int): Int = value.takeIf { it in RANGE } ?: NONE

    fun hidesSlot(mode: Int, slot: Int): Boolean =
        slot in 0..1 && (normalize(mode) and (1 shl slot)) != 0
}
