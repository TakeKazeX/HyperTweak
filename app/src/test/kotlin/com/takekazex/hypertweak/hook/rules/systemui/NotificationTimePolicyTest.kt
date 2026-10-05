package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NotificationTimePolicyTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private fun label(time: String, now: String) =
        NotificationTimePolicy.display(millis(time), millis(now), zone).label

    @Test fun thirtySecondBoundaryDoesNotWaitForAMinuteTick() {
        val time = millis("2026-10-06T07:39:40Z")
        val before = NotificationTimePolicy.display(time, time + 29_999, zone)
        assertEquals(NotificationTimePolicy.Label.NOW, before.label)
        assertEquals(time + 30_000, before.nextUpdateMillis)
        assertEquals(NotificationTimePolicy.Label.TODAY,
            NotificationTimePolicy.display(time, time + 30_000, zone).label)
        assertEquals(NotificationTimePolicy.Label.TODAY,
            NotificationTimePolicy.display(time + 1, time, zone).label)
    }

    @Test fun calendarDatesReplaceElapsedHourAndDayBuckets() {
        val now = "2026-10-06T07:40:00Z"
        assertEquals(NotificationTimePolicy.Label.TODAY, label("2026-10-06T05:41:00Z", now))
        assertEquals(NotificationTimePolicy.Label.YESTERDAY, label("2026-10-05T15:50:00Z", now))
        assertEquals(NotificationTimePolicy.Label.DATE, label("2026-10-04T08:00:00Z", now))
        assertEquals(NotificationTimePolicy.Label.YEAR_DATE, label("2025-12-30T16:00:00Z", now))
        // Yesterday stays yesterday across a year boundary.
        assertEquals(NotificationTimePolicy.Label.YESTERDAY,
            label("2026-12-31T15:00:00Z", "2026-12-31T16:01:00Z"))
    }

    @Test fun nowWindowSurvivesMidnightThenUsesYesterday() {
        val time = millis("2026-10-05T15:59:50Z")
        assertEquals(NotificationTimePolicy.Label.NOW,
            NotificationTimePolicy.display(time, time + 20_000, zone).label)
        assertEquals(NotificationTimePolicy.Label.YESTERDAY,
            NotificationTimePolicy.display(time, time + 30_000, zone).label)
    }

    @Test fun nextCalendarRefreshRespectsDaylightSavingAndTimezoneChanges() {
        val time = millis("2026-03-08T06:00:00Z")
        val now = millis("2026-03-08T06:05:00Z")
        val display = NotificationTimePolicy.display(time, now, ZoneId.of("America/New_York"))
        assertEquals(millis("2026-03-09T04:00:00Z"), display.nextUpdateMillis)
        val crossing = millis("2026-10-05T15:50:00Z")
        val current = millis("2026-10-05T16:10:00Z")
        assertEquals(NotificationTimePolicy.Label.YESTERDAY,
            NotificationTimePolicy.display(crossing, current, zone).label)
        assertEquals(NotificationTimePolicy.Label.TODAY,
            NotificationTimePolicy.display(crossing, current, ZoneId.of("UTC")).label)
    }
}
