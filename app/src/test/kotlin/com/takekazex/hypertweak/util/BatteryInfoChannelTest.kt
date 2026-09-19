package com.takekazex.hypertweak.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryInfoChannelTest {
    @Test fun `metadata cannot masquerade as a battery reading`() {
        val metadata = setOf(BatteryInfoChannel.KEY_UPDATED_AT, BatteryInfoChannel.KEY_SAMPLED_AT, BatteryInfoChannel.KEY_VALID_UNTIL)
        assertEquals(BatteryInfoChannel.Status.MISSING, BatteryInfoChannel.status(metadata, emptyMap(), 1_000))
    }

    @Test fun `a slot is stale without a valid expiry and at its expiry`() {
        val slots = setOf(BatteryInfoChannel.SLOT_FG1_RM)
        assertEquals(BatteryInfoChannel.Status.STALE, BatteryInfoChannel.status(slots, emptyMap(), 1_000))
        assertEquals(BatteryInfoChannel.Status.STALE, BatteryInfoChannel.status(slots, slots.associateWith { 1_000L }, 1_000))
    }

    @Test fun `static data does not hide stale live readings`() {
        val expiry = mapOf(BatteryInfoChannel.SLOT_MODEL_NAME to Long.MAX_VALUE, BatteryInfoChannel.SLOT_FG1_RM to 2_000L)
        assertEquals(BatteryInfoChannel.Status.FRESH, BatteryInfoChannel.status(expiry.keys, expiry, 1_000))
        assertEquals(BatteryInfoChannel.Status.STALE, BatteryInfoChannel.status(expiry.keys, expiry, 3_000))
    }
}
