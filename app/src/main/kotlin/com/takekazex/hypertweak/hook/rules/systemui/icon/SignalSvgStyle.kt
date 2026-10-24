package com.takekazex.hypertweak.hook.rules.systemui.icon

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

        /** An explicit new value, including an invalid one, takes precedence over legacy data. */
        fun resolve(currentPresent: Boolean, currentValue: Int?, legacyValue: Int?): SignalSvgStyle =
            if (currentPresent) fromStored(currentValue ?: 0)
            else if (legacyValue == 3) IOS27 else HYPEROS3
    }
}
