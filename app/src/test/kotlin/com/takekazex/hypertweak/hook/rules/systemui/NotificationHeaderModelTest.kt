package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationHeaderModelTest {
    private val weather = NotificationHeaderModel.WeatherSnapshot(
        region = "北京",
        condition = "多云",
        temperature = 25,
        rainProbability = "30"
    )

    @Test
    fun regionPrecedesTheSingleSelectedWeatherDatum() {
        assertEquals(
            "9月19日 周六 · 北京 · 多云",
            NotificationHeaderModel.formatDateWeather(
                "9月19日 周六",
                weather,
                showRegion = true,
                weatherType = NotificationHeaderModel.WEATHER_CONDITION,
                rainLabel = "降雨"
            )
        )
        assertEquals(
            "9月19日 周六 · 北京 · 25°",
            NotificationHeaderModel.formatDateWeather(
                "9月19日 周六",
                weather,
                showRegion = true,
                weatherType = NotificationHeaderModel.WEATHER_TEMPERATURE,
                rainLabel = "降雨"
            )
        )
        assertEquals(
            "9月19日 周六 · 北京 · 降雨30%",
            NotificationHeaderModel.formatDateWeather(
                "9月19日 周六",
                weather,
                showRegion = true,
                weatherType = NotificationHeaderModel.WEATHER_RAIN,
                rainLabel = "降雨"
            )
        )
    }

    @Test
    fun disabledRegionLeavesOnlyOneWeatherDatum() {
        assertEquals(
            "9月19日 · 多云",
            NotificationHeaderModel.formatDateWeather(
                "9月19日",
                weather,
                showRegion = false,
                weatherType = NotificationHeaderModel.WEATHER_CONDITION,
                rainLabel = "降雨"
            )
        )
    }

    @Test
    fun unavailableSelectedDatumDoesNotShowPlaceholder() {
        val missing = weather.copy(rainProbability = "--")
        assertEquals(
            "9月19日 · 北京",
            NotificationHeaderModel.formatDateWeather(
                "9月19日",
                missing,
                showRegion = true,
                weatherType = NotificationHeaderModel.WEATHER_RAIN,
                rainLabel = "降雨"
            )
        )
    }

    @Test
    fun invalidOptionsFallBackAndScaleIsBounded() {
        assertEquals(
            NotificationHeaderModel.ALIGN_START,
            NotificationHeaderModel.normalizeAlignment(99)
        )
        assertEquals(
            NotificationHeaderModel.WEATHER_CONDITION,
            NotificationHeaderModel.normalizeWeatherType(99)
        )
        assertEquals(0.8f, NotificationHeaderModel.normalizeTimeScale(0.2f))
        assertEquals(2f, NotificationHeaderModel.normalizeTimeScale(4f))
    }

    @Test
    fun headerAndGradientCollapseFromTheSameScrollOffset() {
        assertEquals(-120f, NotificationHeaderMotionModel.rootTranslationY(0f, 120), 0f)
        assertEquals(55f, NotificationHeaderMotionModel.rootTranslationY(75f, 20), 0f)
        assertEquals(180f, NotificationHeaderMotionModel.remainingClipHeight(300f, 120), 0f)
        assertEquals(0f, NotificationHeaderMotionModel.remainingClipHeight(300f, 400), 0f)
    }

    @Test
    fun overscrollAboveTheTopDoesNotCollapseTheHeader() {
        assertEquals(18f, NotificationHeaderMotionModel.rootTranslationY(18f, -24), 0f)
        assertEquals(300f, NotificationHeaderMotionModel.remainingClipHeight(300f, -24), 0f)
    }

    @Test
    fun safeInsetIsConvertedFromWindowCoordinatesOnce() {
        assertEquals(96, NotificationHeaderGeometryModel.safeTopMargin(96, 0))
        assertEquals(24, NotificationHeaderGeometryModel.safeTopMargin(96, 72))
        assertEquals(0, NotificationHeaderGeometryModel.safeTopMargin(72, 96))
    }

    @Test
    fun transformedClockOverflowExtendsHeaderWithoutDoubleCountingCurrentHeight() {
        val firstLayoutOverflow = NotificationHeaderGeometryModel.visualOverflow(
            rootHeight = 400,
            transformedContentBottom = 580
        )
        val expandedLayoutOverflow = NotificationHeaderGeometryModel.visualOverflow(
            rootHeight = 580,
            transformedContentBottom = 760
        )
        assertEquals(180, firstLayoutOverflow)
        assertEquals(firstLayoutOverflow, expandedLayoutOverflow)
        assertEquals(
            580,
            NotificationHeaderGeometryModel.requiredNotificationReserveHeight(
                stockHeaderHeight = 400,
                estimatedHeight = 420,
                visualOverflow = firstLayoutOverflow
            )
        )
        assertEquals(
            500,
            NotificationHeaderGeometryModel.requiredNotificationReserveHeight(
                stockHeaderHeight = 400,
                estimatedHeight = 500,
                visualOverflow = 0
            )
        )
    }
}
