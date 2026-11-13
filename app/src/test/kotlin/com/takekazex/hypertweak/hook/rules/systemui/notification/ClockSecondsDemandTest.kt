package com.takekazex.hypertweak.hook.rules.systemui.notification

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ClockSecondsDemandTest {
    @Test fun offscreenAndSleepStopDemandAndWakeVisibilityRestoresIt() {
        fun active(shown: Boolean = true, window: Boolean = true, awake: Boolean = true,
            retired: Boolean = false) = ClockSecondsDemand.active(true, true, shown, window, awake, retired)
        assertTrue(active())
        assertFalse(active(awake = false)); assertFalse(active(window = false))
        assertFalse(active(shown = false)); assertFalse(active(retired = true))
        assertTrue(active())
        assertFalse(ClockSecondsDemand.active(false, true, true, true, true, false))
    }
    @Test fun dateFormatResolvesOnceAndInvalidatesOnLocaleHourModeAndExplicitReset() {
        val cache = ClockSecondsPattern(); var reads = 0
        fun pattern(locale: Locale, hour24: Boolean) = cache.get(locale, hour24) { l, s -> reads++; "$l:$s" }
        repeat(100) { assertEquals("en_US:Hms", pattern(Locale.US, true)) }
        assertEquals(1, reads)
        assertEquals("en_US:hmsa", pattern(Locale.US, false)); assertEquals(2, reads)
        pattern(Locale.CHINA, false); assertEquals(3, reads)
        cache.clear(); pattern(Locale.CHINA, false); assertEquals(4, reads)
    }
}
