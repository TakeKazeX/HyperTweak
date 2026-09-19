package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.util.BatteryInfoChannel as Channel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Raw, process-local cache. All access belongs to the request worker, never the host main thread. */
internal class BatteryInfoSampler(private val source: Source, private val clock: () -> Long) {
    sealed interface Query {
        data class File(val path: String) : Query
        data class Path(val key: String) : Query
        data class Method(val name: String) : Query
    }

    sealed interface Result {
        data class Value(val text: String) : Result
        data object Unsupported : Result
        data object Unavailable : Result
        data object TimedOut : Result
    }

    interface Source {
        fun read(query: Query): Result
        fun plugged(): Int?
    }

    data class Value(val raw: String, val sampledAt: Long, val validUntil: Long)

    private data class Field(
        val slot: String,
        val ttl: Long,
        val queries: List<Query>,
        val normalize: (String) -> String? = ::text,
        val slave: Boolean = false,
        val charging: Boolean = false
    )

    private data class Entry(
        val value: Value? = null,
        val retryAt: Long = 0,
        val failures: Int = 0
    )

    private val cache = mutableMapOf<String, Entry>()
    private val unsupported = mutableSetOf<Query>()
    private var lastPlugged: Int? = null
    private var circuitUntil = 0L

    fun collect(isActive: () -> Boolean): Map<String, Value> {
        if (!isActive()) return emptyMap()
        val plugged = source.plugged()
        if (plugged != lastPlugged) {
            FIELDS.filter { it.charging }.forEach { cache.remove(it.slot) }
            lastPlugged = plugged
        }
        val reads = mutableMapOf<Query, Result>()
        var halted = clock() < circuitUntil

        fun get(field: Field): Value? {
            val now = clock()
            val old = cache[field.slot] ?: Entry()
            if (halted || !isActive() || now < old.retryAt) return old.value
            var allUnsupported = true
            for (query in field.queries) {
                if (!isActive()) return old.value
                if (query in unsupported) continue
                val result = reads.getOrPut(query) { source.read(query) }
                if (result == Result.Unsupported) {
                    unsupported += query
                    continue
                }
                allUnsupported = false
                if (result == Result.TimedOut) {
                    circuitUntil = clock() + RETRY_MS
                    halted = true
                    break
                }
                val value = (result as? Result.Value)?.text?.let(::text)?.let(field.normalize)
                if (value != null) {
                    val sampledAt = clock()
                    val retryAt = if (field.ttl == STATIC) STATIC else sampledAt + field.ttl
                    // Allow the next foreground request to arrive before declaring a live value stale.
                    val validUntil = if (field.ttl == LIVE_MS) sampledAt + LIVE_MS * 3 else retryAt
                    val fresh = Value(value, sampledAt, validUntil)
                    cache[field.slot] = Entry(fresh, retryAt)
                    return fresh
                }
            }
            val failures = (old.failures + 1).coerceAtMost(5)
            val retryAt = if (allUnsupported) STATIC else clock() + (RETRY_MS shl (failures - 1)).coerceAtMost(SLOW_MS)
            cache[field.slot] = Entry(old.value, retryAt, failures)
            return old.value
        }

        val out = linkedMapOf<String, Value>()
        val topology = get(TOPOLOGY)
        topology?.let { out[TOPOLOGY.slot] = it }
        val dual = topology?.raw == "1"
        for (field in FIELDS) {
            if (field.slave && !dual) continue
            if (field.charging && (plugged == null || plugged == 0)) continue
            get(field)?.let { out[field.slot] = it }
        }
        // The two display slots describe the same stock cycle-count read, not two HAL requests.
        out[Channel.SLOT_CYCLE_COUNT]?.let { out[Channel.SLOT_BATTERY_CYCLE] = it }
        return out
    }

    companion object {
        const val LIVE_MS = 5_000L
        const val SLOW_MS = 300_000L
        const val RETRY_MS = 30_000L
        private const val STATIC = Long.MAX_VALUE
        private const val BATTERY = "/sys/class/power_supply/battery"
        private const val MASTER = "/sys/class/xm_power/fg_master"
        private const val SLAVE = "/sys/class/xm_power/fg_slave"
        private const val FG = "/sys/class/xm_power/fuelgauge/strategy_fg"

        private fun file(path: String) = Query.File(path)
        private fun path(key: String) = Query.Path(key)
        private fun method(name: String) = Query.Method(name)
        private fun text(raw: String): String? = raw.trim().takeUnless {
            it.isEmpty() || it.equals("error", true) || it.equals("null", true) ||
                it.equals("unknown", true) || it.equals("unsupported", true) || it == "-1"
        }
        private fun nonNegative(raw: String): String? = raw.toLongOrNull()?.takeIf { it in 0..100_000_000 }?.toString()
        private fun capacity(raw: String): String? = raw.toDoubleOrNull()?.takeIf {
            it.isFinite() && it > 0 && it <= 100_000_000
        }?.let { raw }
        private fun percent(raw: String): String? = raw.toIntOrNull()?.takeIf { it in 0..100 }?.toString()
        private fun bit(raw: String): String? = raw.takeIf { it == "0" || it == "1" }
        private fun serial(raw: String): String? = raw.takeIf { it.length >= 4 && it.any { c -> c != '0' } }
        private fun date(raw: String): String? = runCatching {
            val format = if (raw.length == 8) DateTimeFormatter.BASIC_ISO_DATE else DateTimeFormatter.ISO_LOCAL_DATE
            LocalDate.parse(raw, format).format(DateTimeFormatter.BASIC_ISO_DATE)
        }.getOrNull()

        private val TOPOLOGY = Field(Channel.SLOT_BATTERY_NUM, STATIC,
            listOf(file("$FG/battery_num"), path("battery_num")), ::bit)

        private val FIELDS = listOf(
            Field(Channel.SLOT_DESIGN_CAPACITY, STATIC,
                listOf(file("$BATTERY/charge_full_design"), path("charge_full_design")), ::capacity),
            Field(Channel.SLOT_CYCLE_COUNT, SLOW_MS,
                listOf(file("$BATTERY/cycle_count"), method("getBatteryCycleCount")), ::nonNegative),
            Field(Channel.SLOT_MODEL_NAME, STATIC, listOf(file("$BATTERY/model_name"))),
            Field(Channel.SLOT_SERIAL_NUMBER, STATIC,
                listOf(file("$BATTERY/serial_number"), file("$MASTER/batt_sn"), path("soh_sn")), ::serial),
            Field(Channel.SLOT_FG1_DESIGN, STATIC,
                listOf(file("$MASTER/design_capacity"), path("fg1_design_capacity")), ::capacity),
            Field(Channel.SLOT_FG2_DESIGN, STATIC,
                listOf(file("$SLAVE/design_capacity"), path("fg2_design_capacity")), ::capacity, slave = true),
            Field(Channel.SLOT_FG1_RM, LIVE_MS,
                listOf(file("$MASTER/rm"), path("fg1_rm")), ::nonNegative),
            Field(Channel.SLOT_FG2_RM, LIVE_MS,
                listOf(file("$SLAVE/rm"), path("fg2_rm")), ::nonNegative, slave = true),
            Field(Channel.SLOT_FG1_SOH, SLOW_MS,
                listOf(file("$MASTER/soh"), path("fg1_soh")), ::percent),
            Field(Channel.SLOT_FG2_SOH, SLOW_MS,
                listOf(file("$SLAVE/soh"), path("fg2_soh")), ::percent, slave = true),
            Field(Channel.SLOT_FG1_CYCLE, SLOW_MS,
                listOf(file("$MASTER/cyclecount"), path("fg1_cycle")), ::nonNegative),
            Field(Channel.SLOT_FG2_CYCLE, SLOW_MS,
                listOf(file("$SLAVE/cyclecount"), path("fg2_cycle")), ::nonNegative, slave = true),
            Field(Channel.SLOT_BATTERY_SOH, SLOW_MS,
                listOf(method("getBatterySoh")), ::percent),
            Field(Channel.SLOT_MANUFACTURING_DATE, STATIC,
                listOf(file("$MASTER/manufacturing_date"), path("manufacturing_date")), ::date),
            Field(Channel.SLOT_FIRST_USAGE_DATE, STATIC,
                listOf(file("$MASTER/first_usage_date"), path("first_usage_date")), ::date),
            Field(Channel.SLOT_SOH_SN, STATIC,
                listOf(file("$MASTER/batt_sn"), path("soh_sn")), ::serial),
            // OS4's fg2_soh_sn HAL key maps to /fg_slave/soh, NOT the serial-number node.
            Field(Channel.SLOT_FG2_SOH_SN, STATIC, listOf(file("$SLAVE/batt_sn")), ::serial, slave = true),
            Field(Channel.SLOT_AUTHENTIC, STATIC,
                listOf(file("$FG/authentic"), path("authentic")), ::bit),
            Field(Channel.SLOT_SLAVE_AUTHENTIC, STATIC,
                listOf(file("$FG/slave_authentic"), path("slave_authentic")), ::bit, slave = true),
            Field(Channel.SLOT_CHARGE_TYPE, LIVE_MS,
                listOf(file("/sys/class/power_supply/usb/type"), method("getBatteryChargeType")), charging = true),
            Field(Channel.SLOT_CHARGE_POWER, LIVE_MS,
                listOf(method("getChargingPowerMax")), ::nonNegative, charging = true),
            Field(Channel.SLOT_FCC, SLOW_MS,
                listOf(file("$BATTERY/charge_full"), method("getBatteryChargeFull")), ::capacity),
            Field(Channel.SLOT_PD_AUTH, LIVE_MS,
                listOf(method("getPdAuthentication")), ::bit, charging = true)
        )
    }
}
