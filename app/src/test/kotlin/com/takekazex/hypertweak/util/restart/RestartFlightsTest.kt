package com.takekazex.hypertweak.util.restart

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test

class RestartFlightsTest {
    @Test fun `overlapping requests from different pages share only overlapping packages`() {
        val flights = RestartFlights()
        var starts = 0
        val first = flights.getOrStart("systemui") { starts++; CompletableDeferred() }
        val other = flights.getOrStart("settings") { starts++; CompletableDeferred() }
        val duplicate = flights.getOrStart("systemui") { error("duplicate kill") }
        assertSame(first, duplicate)
        assertNotSame(first, other)
        assertEquals(2, starts)
        (first as CompletableDeferred).complete(true)
        assertNotSame(first, flights.getOrStart("systemui") { CompletableDeferred() })
    }

    @Test fun `failure releases target for a later explicit retry`() {
        val flights = RestartFlights()
        val first = flights.getOrStart("systemui") { CompletableDeferred() }
        (first as CompletableDeferred).completeExceptionally(IllegalStateException("Root denied"))
        assertNotSame(first, flights.getOrStart("systemui") { CompletableDeferred() })
    }

    @Test fun `synchronously completed operation does not retain a stale claim`() {
        val flights = RestartFlights()
        val first = flights.getOrStart("settings") { CompletableDeferred(true) }
        assertNotSame(first, flights.getOrStart("settings") { CompletableDeferred(true) })
    }
}
