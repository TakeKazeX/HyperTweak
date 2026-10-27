package com.takekazex.hypertweak.hook.nativebridge

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test

class RecentsCardHapticsTest {
    private fun ms(value: Long) = value * 1_000_000L
    private class Driver : RecentsCardHaptics.Driver {
        var ready = true
        var plays = 0
        var closes = 0
        var preparing: () -> Unit = {}
        override fun prepare(context: Context): Boolean { preparing(); return ready }
        override fun play() { plays++ }
        override fun close() { closes++ }
    }

    @Test fun `bursts are immediate and never drain after the gesture`() {
        var now = ms(100)
        val driver = Driver()
        val haptic = RecentsCardHaptics({ now }, driver)
        val context = ContextWrapper(null)
        for (time in 101L..160L) {
            now = ms(time)
            haptic.play(context, now)
        }
        assertEquals(1, driver.plays)
        now = ms(1000)
        assertTrue(haptic.prepare(context))
        assertEquals(1, driver.plays)
        haptic.play(context, now)
        assertEquals(2, driver.plays)
    }

    @Test fun `retired publisher cannot reopen its driver and a new publisher starts fresh`() {
        var now = ms(100)
        val context = ContextWrapper(null)
        val oldDriver = Driver()
        val old = RecentsCardHaptics({ now }, oldDriver)
        old.close(); old.close()
        assertEquals(1, oldDriver.closes)
        assertFalse(old.prepare(context))
        now = ms(200)
        old.play(context, now)
        assertEquals(0, oldDriver.plays)
        val nextDriver = Driver()
        val next = RecentsCardHaptics({ now }, nextDriver)
        now = ms(210)
        next.play(context, ms(195)) // In-flight event from before the new attachment.
        assertEquals(0, nextDriver.plays)
        next.play(context, now)
        assertEquals(1, nextDriver.plays)
    }

    @Test fun `slow cold preparation cannot turn an old event into late feedback`() {
        var now = ms(100)
        val driver = Driver()
        val haptic = RecentsCardHaptics({ now }, driver)
        now = ms(110)
        driver.preparing = { now = ms(200) }
        haptic.play(ContextWrapper(null), ms(110))
        assertEquals(0, driver.plays)
    }

    @Test fun `live disable reset rejects still fresh events from before the boundary`() {
        var now = ms(100)
        val driver = Driver()
        val haptic = RecentsCardHaptics({ now }, driver)
        now = ms(120); haptic.reset()
        now = ms(130)
        haptic.play(ContextWrapper(null), ms(110))
        assertEquals(0, driver.plays)
        haptic.play(ContextWrapper(null), now)
        assertEquals(1, driver.plays)
    }
}
