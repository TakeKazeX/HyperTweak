package com.takekazex.hypertweak.util

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.net.toUri

/**
 * Cross-process channel for the battery-info page.
 *
 * The module's settings UI runs in a normal-app process, which cannot read the privileged battery
 * parameters (design capacity, manufacturing date, SOH, `qcom-battery` sysfs, `IMiCharge`, ...).
 * Those are read by a hooked privileged process (`com.miui.securitycenter`, which runs as
 * `android.uid.system`), sampled only on a foreground page request and pushed through the battery
 * provider. The UI reads the snapshot back through the same provider.
 *
 * The snapshot is a `Bundle` mapping a stable slot id (see the SLOT_* constants) to an
 * already-formatted display string (units included, e.g. "5000 mAh", "2023-06-15", "是"). The
 * producer formats once; the reader just renders, and falls back to "不可用" when a slot is absent.
 */
object BatteryInfoChannel {
    const val AUTHORITY = "com.takekazex.hypertweak.batteryprovider"
    const val METHOD_SET = "set"
    const val METHOD_GET = "get"

    const val PACKAGE = "com.miui.securitycenter"
    const val PERMISSION = "com.takekazex.hypertweak.permission.BATTERY_INFO"
    const val ACTION_REQUEST = "com.takekazex.hypertweak.action.BATTERY_INFO_REQUEST"
    const val ACTION_STOP = "com.takekazex.hypertweak.action.BATTERY_INFO_STOP"
    const val EXTRA_SESSION = "session"
    const val EXTRA_ISSUED_AT = "issued_at"
    const val REQUEST_INTERVAL_MS = 5_000L
    const val REQUEST_LIFETIME_MS = 10_000L

    /** Elapsed-realtime timestamps of successful hardware reads, not the time a cache was pushed. */
    const val KEY_UPDATED_AT = "updated_at"
    const val KEY_SAMPLED_AT = "sampled_at"
    const val KEY_VALID_UNTIL = "valid_until"

    // ─── privileged slot ids (also the Bundle keys) ──────────────────────────
    const val SLOT_DESIGN_CAPACITY = "design_capacity"
    const val SLOT_CYCLE_COUNT = "cycle_count"
    const val SLOT_MODEL_NAME = "model_name"
    const val SLOT_SERIAL_NUMBER = "serial_number"
    const val SLOT_FG1_DESIGN = "fg1_design_capacity"
    const val SLOT_FG2_DESIGN = "fg2_design_capacity"
    const val SLOT_FG1_RM = "fg1_rm"
    const val SLOT_FG2_RM = "fg2_rm"
    const val SLOT_FG1_SOH = "fg1_soh"
    const val SLOT_FG2_SOH = "fg2_soh"
    const val SLOT_FG1_CYCLE = "fg1_cycle"
    const val SLOT_FG2_CYCLE = "fg2_cycle"
    const val SLOT_BATTERY_SOH = "get_battery_soh"
    const val SLOT_BATTERY_CYCLE = "get_battery_cycle"
    const val SLOT_MANUFACTURING_DATE = "manufacturing_date"
    const val SLOT_FIRST_USAGE_DATE = "first_usage_date"
    const val SLOT_BATTERY_NUM = "battery_num"
    const val SLOT_SOH_SN = "soh_sn"
    const val SLOT_FG2_SOH_SN = "fg2_soh_sn"
    const val SLOT_AUTHENTIC = "authentic"
    const val SLOT_SLAVE_AUTHENTIC = "slave_authentic"
    const val SLOT_CHARGE_TYPE = "get_battery_charge_type"
    const val SLOT_CHARGE_POWER = "get_charging_power_max"
    const val SLOT_IBAT = "get_battery_ibat"
    const val SLOT_FCC = "get_battery_charge_full"
    const val SLOT_TYPEC_PORT = "get_typec_port_num"
    const val SLOT_PD_AUTH = "get_pd_authentication"

    val slots = setOf(
        SLOT_DESIGN_CAPACITY, SLOT_CYCLE_COUNT, SLOT_MODEL_NAME, SLOT_SERIAL_NUMBER,
        SLOT_FG1_DESIGN, SLOT_FG2_DESIGN, SLOT_FG1_RM, SLOT_FG2_RM, SLOT_FG1_SOH, SLOT_FG2_SOH,
        SLOT_FG1_CYCLE, SLOT_FG2_CYCLE, SLOT_BATTERY_SOH, SLOT_BATTERY_CYCLE,
        SLOT_MANUFACTURING_DATE, SLOT_FIRST_USAGE_DATE, SLOT_BATTERY_NUM, SLOT_SOH_SN,
        SLOT_FG2_SOH_SN, SLOT_AUTHENTIC, SLOT_SLAVE_AUTHENTIC, SLOT_CHARGE_TYPE,
        SLOT_CHARGE_POWER, SLOT_IBAT, SLOT_FCC, SLOT_TYPEC_PORT, SLOT_PD_AUTH
    )

    enum class Status { MISSING, FRESH, STALE }

    fun status(values: Set<String>, validUntil: Map<String, Long>, now: Long): Status {
        val present = values.intersect(slots)
        if (present.isEmpty()) return Status.MISSING
        return if (present.all { now < (validUntil[it] ?: 0) }) Status.FRESH else Status.STALE
    }

    fun request(context: Context, session: String, stop: Boolean = false) {
        val intent = Intent(if (stop) ACTION_STOP else ACTION_REQUEST).apply {
            setPackage(PACKAGE)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(EXTRA_SESSION, session)
            putExtra(EXTRA_ISSUED_AT, SystemClock.elapsedRealtime())
        }
        // The receiver requires PERMISSION of the sender. The hooked host does not hold it.
        context.sendBroadcast(intent)
    }

    fun uri() = "content://$AUTHORITY".toUri()

    // ─── formatting helpers (shared by the producer hook and the reader) ─────
    // The fuel-gauge exposes charge/current in µAh/µA; a value well above any real mAh/mA is µAh,
    // so divide by 1000. Abs for current (sign is device-dependent and meaningless to the reader).

    fun mah(raw: Int?): String? = raw?.let { "${if (kotlin.math.abs(it) > 100_000) it / 1000 else it} mAh" }

    fun mah(raw: Double?): String? = raw?.let { v ->
        val m = if (kotlin.math.abs(v) > 100_000) v / 1000.0 else v
        "${if (m == m.toLong().toDouble()) m.toLong().toString() else m.toString()} mAh"
    }

    fun ma(raw: Int?): String? = raw?.let { v ->
        val m = kotlin.math.abs(v)
        "${if (m > 100_000) m / 1000 else m} mA"
    }

    /** Formats a `yyyyMMdd` date as `yyyy-MM-dd`; other forms pass through. */
    fun date(raw: String?): String? {
        val s = raw?.trim()?.takeUnless { it.isBlank() } ?: return null
        if (s.length == 8 && s.all { it.isDigit() }) {
            return "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}"
        }
        return s
    }
}
