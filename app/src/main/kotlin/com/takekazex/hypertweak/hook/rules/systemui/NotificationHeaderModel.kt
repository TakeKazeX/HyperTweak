package com.takekazex.hypertweak.hook.rules.systemui

import kotlin.math.max

/** Pure policy shared by the notification-header hook, settings UI, and JVM tests. */
object NotificationHeaderModel {
    const val ALIGN_START = 0
    const val ALIGN_CENTER = 1
    const val ALIGN_END = 2

    const val WEATHER_CONDITION = 0
    const val WEATHER_TEMPERATURE = 1
    const val WEATHER_RAIN = 2

    const val MIN_TIME_SCALE = 0.8f
    const val MAX_TIME_SCALE = 2f
    const val DEFAULT_TIME_SCALE = 1f

    data class WeatherSnapshot(
        val region: String?,
        val condition: String?,
        val temperature: Int?,
        val rainProbability: String?
    )

    fun normalizeAlignment(value: Int): Int = when (value) {
        ALIGN_CENTER, ALIGN_END -> value
        else -> ALIGN_START
    }

    fun normalizeWeatherType(value: Int): Int = when (value) {
        WEATHER_TEMPERATURE, WEATHER_RAIN -> value
        else -> WEATHER_CONDITION
    }

    fun normalizeTimeScale(value: Float): Float =
        value.takeIf(Float::isFinite)?.coerceIn(MIN_TIME_SCALE, MAX_TIME_SCALE)
            ?: DEFAULT_TIME_SCALE

    /**
     * Produces one compact line in the requested order:
     * date + optional region + exactly one selected weather datum.
     * Missing provider values are omitted instead of exposing Xiaomi's `--` placeholders.
     */
    fun formatDateWeather(
        baseDate: String,
        weather: WeatherSnapshot?,
        showRegion: Boolean,
        weatherType: Int,
        rainLabel: String
    ): String {
        val base = baseDate.trim()
        if (base.isEmpty() || weather == null) return baseDate

        val parts = ArrayList<String>(3)
        parts += base
        if (showRegion) weather.region.cleanValue()?.let(parts::add)

        val selected = when (normalizeWeatherType(weatherType)) {
            WEATHER_TEMPERATURE -> weather.temperature?.let { "$it°" }
            WEATHER_RAIN -> weather.rainProbability.cleanRain()?.let { value ->
                "$rainLabel${if (value.endsWith('%')) value else "$value%"}"
            }
            else -> weather.condition.cleanValue()
        }
        selected?.let(parts::add)
        return parts.joinToString(" · ")
    }

    private fun String?.cleanValue(): String? = this?.trim()?.takeIf {
        it.isNotEmpty() && it != "--" && it != "null"
    }

    private fun String?.cleanRain(): String? = cleanValue()?.removeSuffix("%")?.trim()
        ?.takeIf { value -> value.toFloatOrNull()?.let { it >= 0f } == true }
}

/** Shared scroll geometry for the header view and its gradient clip. */
internal object NotificationHeaderMotionModel {
    fun rootTranslationY(stretchTranslationY: Float, scrollY: Int): Float =
        stretchTranslationY - scrollY.coerceAtLeast(0)

    fun remainingClipHeight(headerHeight: Float, scrollY: Int): Float =
        max(0f, headerHeight - scrollY.coerceAtLeast(0))
}

internal object NotificationHeaderGeometryModel {
    fun safeTopMargin(safeInsetTopInWindow: Int, rootTopInWindow: Int): Int =
        (safeInsetTopInWindow - rootTopInWindow).coerceAtLeast(0)

    fun visualOverflow(rootHeight: Int, transformedContentBottom: Int): Int =
        (transformedContentBottom - rootHeight).coerceAtLeast(0)

    fun requiredNotificationReserveHeight(
        stockHeaderHeight: Int,
        estimatedHeight: Int,
        visualOverflow: Int
    ): Int {
        val baseline = stockHeaderHeight.coerceAtLeast(0)
        return maxOf(
            baseline,
            estimatedHeight.coerceAtLeast(0),
            baseline + visualOverflow.coerceAtLeast(0)
        )
    }
}
