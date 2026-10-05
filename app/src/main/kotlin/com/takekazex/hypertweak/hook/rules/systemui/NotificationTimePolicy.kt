package com.takekazex.hypertweak.hook.rules.systemui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar dates deliberately replace rounded elapsed days; future timestamps stay absolute. */
internal object NotificationTimePolicy {
    const val NOW_WINDOW_MILLIS = 30_000L
    enum class Label { NOW, TODAY, YESTERDAY, DATE, YEAR_DATE }
    data class Display(val label: Label, val nextUpdateMillis: Long)

    fun display(timeMillis: Long, nowMillis: Long, zone: ZoneId): Display {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val date = Instant.ofEpochMilli(timeMillis).atZone(zone).toLocalDate()
        val today = now.toLocalDate()
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val age = nowMillis - timeMillis
        if (age in 0 until NOW_WINDOW_MILLIS) {
            return Display(Label.NOW, timeMillis + NOW_WINDOW_MILLIS)
        }
        return Display(label(date, today), midnight)
    }

    private fun label(date: LocalDate, today: LocalDate): Label = when {
        date == today -> Label.TODAY
        date == today.minusDays(1) -> Label.YESTERDAY
        date.year == today.year -> Label.DATE
        else -> Label.YEAR_DATE
    }
}
