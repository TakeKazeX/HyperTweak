package com.takekazex.hypertweak.settings.contract

/** Persisted artwork families and geometry of the bundled, unmodified SVGs. */
enum class SignalSvgStyle(
    val storedValue: Int,
    val stackedSplitFraction: Float
) {
    HYPEROS3(0, .632f),
    IOS27(1, .610455f);

    companion object {
        fun fromStored(value: Int): SignalSvgStyle =
            entries.firstOrNull { it.storedValue == value } ?: HYPEROS3


    }
}
