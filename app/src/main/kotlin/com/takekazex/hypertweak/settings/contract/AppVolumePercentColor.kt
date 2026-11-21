package com.takekazex.hypertweak.settings.contract

/** Append entries to keep existing stored selections stable. */
enum class AppVolumePercentColor(val color: Int? = null) {
    INHERITED,
    APP_ICON,
    BLUE(0xFF007AFF.toInt()),
    GREEN(0xFF4CAF50.toInt()),
    ORANGE(0xFFFF9800.toInt()),
    RED(0xFFF44336.toInt()),
    PURPLE(0xFF9C27B0.toInt()),
    NEUTRAL;

    companion object {
        fun fromStored(value: Int) = entries.getOrNull(value) ?: INHERITED
    }
}
