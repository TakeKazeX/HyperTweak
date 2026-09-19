package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.util.BatteryInfoChannel as Channel
import com.takekazex.hypertweak.hook.rules.securitycenter.BatteryInfoSampler.Query
import com.takekazex.hypertweak.hook.rules.securitycenter.BatteryInfoSampler.Result
import org.junit.Assert.*
import org.junit.Test

class BatteryInfoSamplerTest {
    private class Harness : BatteryInfoSampler.Source {
        var now = 1_000L
        var plugged: Int? = 1
        var active = true
        var afterRead: ((Query) -> Unit)? = null
        val calls = mutableListOf<Query>()
        val responses = mutableMapOf<Query, Result>(Query.Path("battery_num") to Result.Value("0"))
        val sampler = BatteryInfoSampler(this) { now }
        override fun plugged() = plugged
        override fun read(query: Query): Result {
            calls += query
            afterRead?.invoke(query)
            return responses[query] ?: Result.Unsupported
        }
        fun value(query: Query, value: String) { responses[query] = Result.Value(value) }
        fun collect() = sampler.collect { active }
        fun count(query: Query) = calls.count { it == query }
    }

    private fun slave(query: Query) = when (query) {
        is Query.File -> "/fg_slave/" in query.path || "slave_authentic" in query.path
        is Query.Path -> query.key.startsWith("fg2_") || query.key == "slave_authentic"
        is Query.Method -> false
    }

    @Test fun `no demand means no source reads`() {
        val h = Harness()
        assertTrue(h.calls.isEmpty())
        h.active = false
        assertTrue(h.collect().isEmpty())
        assertTrue(h.calls.isEmpty())
    }

    @Test fun `single and unknown topologies never probe slave fields`() {
        for (topology in listOf("0", "error", "", "2", "-1")) {
            val h = Harness()
            h.value(Query.Path("battery_num"), topology)
            repeat(20) { h.collect(); h.now += 5_000 }
            assertTrue("topology=$topology", h.calls.none(::slave))
        }
    }

    @Test fun `dual topology permits supported slave fields but never the broken serial key`() {
        val h = Harness()
        h.value(Query.Path("battery_num"), "1")
        h.value(Query.Path("fg2_design_capacity"), "3800000")
        h.value(Query.Path("fg2_soh"), "97")
        h.value(Query.Path("fg2_soh_sn"), "97")
        val sample = h.collect()
        assertEquals("3800000", sample[Channel.SLOT_FG2_DESIGN]?.raw)
        assertEquals("97", sample[Channel.SLOT_FG2_SOH]?.raw)
        assertFalse(sample.containsKey(Channel.SLOT_FG2_SOH_SN))
        assertEquals(0, h.count(Query.Path("fg2_soh_sn")))
    }

    @Test fun `capability is resolved before any slave read`() {
        val h = Harness()
        h.value(Query.Path("battery_num"), "1")
        h.collect()
        assertTrue(h.calls.indexOf(Query.Path("battery_num")) < h.calls.indexOfFirst(::slave))
    }

    @Test fun `static slow and live fields have independent cache lifetimes`() {
        val h = Harness()
        val design = Query.Path("fg1_design_capacity")
        val soh = Query.Path("fg1_soh")
        val rm = Query.Path("fg1_rm")
        h.value(design, "7500000"); h.value(soh, "96"); h.value(rm, "5000000")
        h.collect()
        h.now += BatteryInfoSampler.LIVE_MS
        h.collect()
        assertEquals(1, h.count(design))
        assertEquals(1, h.count(soh))
        assertEquals(2, h.count(rm))
        h.now += BatteryInfoSampler.SLOW_MS
        h.collect()
        assertEquals(1, h.count(design))
        assertEquals(2, h.count(soh))
        assertEquals(3, h.count(rm))
    }

    @Test fun `shared serial and cycle sources are queried once per sample`() {
        val h = Harness()
        val serial = Query.Path("soh_sn")
        val cycles = Query.Method("getBatteryCycleCount")
        h.value(serial, "SERIAL1234"); h.value(cycles, "129")
        val values = h.collect()
        assertEquals(1, h.count(serial))
        assertEquals(1, h.count(cycles))
        assertEquals(values[Channel.SLOT_CYCLE_COUNT], values[Channel.SLOT_BATTERY_CYCLE])
        assertEquals(values[Channel.SLOT_SERIAL_NUMBER]?.raw, values[Channel.SLOT_SOH_SN]?.raw)
    }

    @Test fun `readable sysfs avoids HAL fallbacks`() {
        val h = Harness()
        h.value(Query.File("/sys/class/xm_power/fg_master/rm"), "5200000")
        assertEquals("5200000", h.collect()[Channel.SLOT_FG1_RM]?.raw)
        assertEquals(0, h.count(Query.Path("fg1_rm")))
    }

    @Test fun `definitely unsupported sources are not retried even after slow TTL`() {
        val h = Harness()
        h.collect()
        val first = h.calls.size
        h.now += 10 * BatteryInfoSampler.SLOW_MS
        h.collect()
        assertEquals(first, h.calls.size)
    }

    @Test fun `transient failures back off and recover only on demand`() {
        val h = Harness()
        val query = Query.Path("fg1_rm")
        h.responses[query] = Result.Unavailable
        h.collect()
        repeat(5) { h.now += 5_000; h.collect() }
        assertEquals(1, h.count(query))
        h.now += 5_000
        h.value(query, "5100000")
        assertEquals("5100000", h.collect()[Channel.SLOT_FG1_RM]?.raw)
        assertEquals(2, h.count(query))
    }

    @Test fun `failure preserves the successful value and its true age`() {
        val h = Harness()
        val query = Query.Path("fg1_rm")
        h.value(query, "5100000")
        val old = h.collect().getValue(Channel.SLOT_FG1_RM)
        h.now += BatteryInfoSampler.LIVE_MS
        h.responses[query] = Result.Unavailable
        val cached = h.collect().getValue(Channel.SLOT_FG1_RM)
        assertEquals(old, cached)
        h.now += BatteryInfoSampler.LIVE_MS * 3
        assertTrue(h.now >= cached.validUntil)
    }

    @Test fun `HAL timeout cuts off remaining fields and opens a circuit`() {
        val h = Harness()
        h.responses[Query.Path("battery_num")] = Result.TimedOut
        h.collect()
        assertEquals(listOf(Query.File("/sys/class/xm_power/fuelgauge/strategy_fg/battery_num"), Query.Path("battery_num")), h.calls)
        h.now += 5_000
        h.collect()
        assertEquals(2, h.calls.size)
        h.now += BatteryInfoSampler.RETRY_MS
        h.value(Query.Path("battery_num"), "0")
        assertEquals("0", h.collect()[Channel.SLOT_BATTERY_NUM]?.raw)
        assertTrue(h.calls.size > 2)
    }

    @Test fun `stop during one source read prevents all subsequent reads`() {
        val h = Harness()
        h.afterRead = { h.active = false }
        h.collect()
        assertEquals(1, h.calls.size)
    }

    @Test fun `unplug clears charging values and replug invalidates their TTL`() {
        val h = Harness()
        val type = Query.Method("getBatteryChargeType")
        val pd = Query.Method("getPdAuthentication")
        h.value(type, "PD"); h.value(pd, "1")
        assertEquals("PD", h.collect()[Channel.SLOT_CHARGE_TYPE]?.raw)
        h.plugged = 0
        assertFalse(h.collect().containsKey(Channel.SLOT_CHARGE_TYPE))
        assertEquals(1, h.count(type))
        assertEquals(1, h.count(pd))
        h.plugged = 2
        h.value(type, "USB")
        assertEquals("USB", h.collect()[Channel.SLOT_CHARGE_TYPE]?.raw)
        assertEquals(2, h.count(type))
    }

    @Test fun `invalid readings do not become values but valid zero counters survive`() {
        val h = Harness()
        h.value(Query.Path("fg1_design_capacity"), "NaN")
        h.value(Query.Path("fg1_rm"), "-1")
        h.value(Query.Path("fg1_soh"), "101")
        h.value(Query.Path("fg1_cycle"), "0")
        h.value(Query.Method("getBatterySoh"), "error")
        h.value(Query.Path("manufacturing_date"), "20260230")
        h.value(Query.Path("first_usage_date"), "00000000")
        h.value(Query.Method("getPdAuthentication"), "0")
        val values = h.collect()
        assertNull(values[Channel.SLOT_FG1_DESIGN])
        assertNull(values[Channel.SLOT_FG1_RM])
        assertNull(values[Channel.SLOT_FG1_SOH])
        assertNull(values[Channel.SLOT_BATTERY_SOH])
        assertNull(values[Channel.SLOT_MANUFACTURING_DATE])
        assertNull(values[Channel.SLOT_FIRST_USAGE_DATE])
        assertEquals("0", values[Channel.SLOT_FG1_CYCLE]?.raw)
        assertEquals("0", values[Channel.SLOT_PD_AUTH]?.raw)
    }

    @Test fun `a permission denied direct file still allows a HAL fallback`() {
        val h = Harness()
        h.responses[Query.File("/sys/class/xm_power/fg_master/rm")] = Result.Unavailable
        h.value(Query.Path("fg1_rm"), "5000000")
        assertEquals("5000000", h.collect()[Channel.SLOT_FG1_RM]?.raw)
    }
}
