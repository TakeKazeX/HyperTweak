package com.takekazex.hypertweak.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import com.takekazex.hypertweak.R
import java.io.File
import java.lang.reflect.Method
import java.util.Locale

/**
 * Reads the battery-related parameters the device exposes, for HyperTweak's own "电池信息" debug page.
 *
 * The module's settings run in a normal-app process, so it can always read the `BatteryManager`
 * tier (sticky broadcast + `getIntProperty`) but NOT the privileged tier (`IMiCharge`, design
 * capacity, `qcom-battery` sysfs, manufacturing date, ...). Those are read by
 * `hook/rules/securitycenter/BatteryInfoHooker` in `com.miui.securitycenter` (system uid) and pushed
 * into [BatteryInfoProvider]; [read] overlays that snapshot on the live basic tier. A value the
 * snapshot does not carry, and that a normal process cannot read, shows a localized
 * "unavailable" value.
 *
 * The [context] passed to [read] is already localized to the selected app language (it comes from
 * `LocalContext.current`, which `MainActivity` wraps with `LocaleHelper.getLocalizedContext`), so
 * every label and status/unit word resolves through the module resources and follows the language.
 *
 * Nothing here may throw out of [read]; every read is guarded.
 */
object BatteryInfoReader {

    data class Row(val label: String, val value: String)
    data class Section(val title: String, val rows: List<Row>)

    private const val BATTERY_SYSFS = "/sys/class/power_supply/battery"
    private const val THERMAL_SYSFS = "/sys/class/thermal/thermal_message"

    // ─── snapshot overlay ────────────────────────────────────────────────────

    private fun readSnapshot(context: Context): Bundle? = runCatching {
        context.contentResolver.call(BatteryInfoChannel.uri(), BatteryInfoChannel.METHOD_GET, null, null)
    }.getOrNull()

    private fun snap(bundle: Bundle?, key: String): String? = bundle?.getString(key)

    /** True when the privileged snapshot already carries at least one battery value. */
    fun hasPrivilegedSnapshot(context: Context): Boolean {
        val snap = readSnapshot(context) ?: return false
        return snap.keySet().any { it != BatteryInfoChannel.KEY_UPDATED_AT }
    }

    // ─── BatteryManager / broadcast ──────────────────────────────────────────

    private fun sticky(context: Context): Intent? =
        runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()

    private fun batteryManager(context: Context): BatteryManager? =
        runCatching { context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager }.getOrNull()

    private fun intProperty(context: Context, type: Int): Int? {
        val bm = batteryManager(context) ?: return null
        return runCatching {
            val method = BatteryManager::class.java.getMethod("getIntProperty", Int::class.javaPrimitiveType)
            val v = method.invoke(bm, type) as? Int
            if (v == Int.MIN_VALUE) null else v
        }.getOrNull()
    }

    // ─── label / formatting helpers ──────────────────────────────────────────

    private fun pluggedLabel(context: Context, value: Int): String = when (value) {
        0 -> context.getString(R.string.battery_plugged_none)
        1 -> context.getString(R.string.battery_plugged_ac)
        2 -> context.getString(R.string.battery_plugged_usb)
        4 -> context.getString(R.string.battery_plugged_wireless)
        8 -> context.getString(R.string.battery_plugged_reverse)
        else -> context.getString(R.string.battery_plugged_type_fmt, value)
    }

    private fun healthLabel(context: Context, value: Int): String = when (value) {
        1 -> context.getString(R.string.battery_health_unknown)
        2 -> context.getString(R.string.battery_health_good)
        3 -> context.getString(R.string.battery_health_overheat)
        4 -> context.getString(R.string.battery_health_dead)
        5 -> context.getString(R.string.battery_health_overvoltage)
        6 -> context.getString(R.string.battery_health_unspecified)
        7 -> context.getString(R.string.battery_health_cold)
        else -> context.getString(R.string.battery_health_unknown)
    }

    private fun statusLabel(context: Context, value: Int): String = when (value) {
        1 -> context.getString(R.string.battery_status_unknown)
        2 -> context.getString(R.string.battery_status_charging)
        3 -> context.getString(R.string.battery_status_discharging)
        4 -> context.getString(R.string.battery_status_not_charging)
        5 -> context.getString(R.string.battery_status_full)
        else -> context.getString(R.string.battery_status_unknown)
    }

    private fun halfCelsius(context: Context, tenths: Int?): String =
        if (tenths == null || tenths == Int.MIN_VALUE || tenths <= -500) context.getString(R.string.battery_unavailable)
        else "${tenths / 10.0} °C"

    private fun milliVolt(context: Context, mv: Int?): String =
        if (mv == null || mv <= 0) context.getString(R.string.battery_unavailable) else "${mv / 1000.0} V"

    private fun sohRating(context: Context, sohPercent: Int?): String = when {
        sohPercent == null || sohPercent < 0 -> context.getString(R.string.battery_unreadable)
        sohPercent >= 90 -> context.getString(R.string.battery_soh_excellent)
        sohPercent >= 80 -> context.getString(R.string.battery_soh_good)
        sohPercent >= 60 -> context.getString(R.string.battery_soh_fair)
        else -> context.getString(R.string.battery_soh_replace)
    }

    /** Digits only — tolerates a localized unit suffix (e.g. "120 次"/"120 cycles"/"97 %"). */
    private fun digits(v: String?): Int? = v?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    /** Computes live charge wattage = voltage (mV) × |current (µA)| / 1e9, while plugged. */
    private fun liveChargePower(context: Context, intent: Intent?, out: MutableList<Row>) {
        if (intent == null) return
        val plugged = intent.getIntExtra("plugged", 0)
        if (plugged == 0) return
        val voltMv = intent.getIntExtra("voltage", -1)
        val currentUa = intProperty(context, 2) ?: return
        if (voltMv <= 0 || currentUa == 0) return
        val watt = voltMv * kotlin.math.abs(currentUa).toDouble() / 1e9
        if (watt < 0.05) return
        out += Row("${context.getString(R.string.battery_lbl_live_power)} · V×I", String.format(Locale.US, "%.1f W", watt))
    }

    // ─── public API ──────────────────────────────────────────────────────────

    /** Reads all supported battery parameters, groupable into sections for display. */
    fun read(context: Context): List<Section> {
        val intent = sticky(context)
        val snap = readSnapshot(context)
        val dual = snap(snap, BatteryInfoChannel.SLOT_BATTERY_NUM) == "1" ||
            snap(snap, BatteryInfoChannel.SLOT_FG2_DESIGN) != null ||
            snap(snap, BatteryInfoChannel.SLOT_FG2_SOH) != null ||
            snap(snap, BatteryInfoChannel.SLOT_FG2_SOH_SN) != null
        val sections = mutableListOf<Section>()

        // 基础信息 (always-available)
        val basic = mutableListOf<Row>()
        val level = intent?.getIntExtra("level", -1) ?: -1
        val scale = intent?.getIntExtra("scale", -1) ?: -1
        val percent = if (level in 0..100 && scale > 0) level * 100 / scale else intProperty(context, 4)
        basic += Row("${context.getString(R.string.battery_lbl_level)} · level/scale", if (percent in 0..100) "$percent %" else context.getString(R.string.battery_unavailable))
        basic += Row("${context.getString(R.string.battery_lbl_status)} · status", statusLabel(context, intent?.getIntExtra("status", -1) ?: -1))
        basic += Row("${context.getString(R.string.battery_lbl_plugged)} · plugged", pluggedLabel(context, intent?.getIntExtra("plugged", 0) ?: 0))
        basic += Row("${context.getString(R.string.battery_lbl_health)} · health", healthLabel(context, intent?.getIntExtra("health", -1) ?: -1))
        val temp = intent?.getIntExtra("temperature", Int.MIN_VALUE) ?: sysfsInt("$BATTERY_SYSFS/temperature")?.let { it * 10 }
        basic += Row("${context.getString(R.string.battery_lbl_temp)} · temperature", halfCelsius(context, temp))
        val voltage = intent?.getIntExtra("voltage", Int.MIN_VALUE) ?: sysfsInt("$BATTERY_SYSFS/voltage_now")?.let { it / 1000 }
        basic += Row("${context.getString(R.string.battery_lbl_voltage)} · voltage", milliVolt(context, voltage))
        basic += Row("${context.getString(R.string.battery_lbl_tech)} · technology", intent?.getStringExtra("technology") ?: sysfsRaw("$BATTERY_SYSFS/technology") ?: context.getString(R.string.battery_unavailable))
        sections += Section(context.getString(R.string.battery_sec_basic), basic)

        // 容量 (mix of snapshot + framework)
        val capacity = mutableListOf<Row>()
        capacity += Row("${context.getString(R.string.battery_lbl_nominal)} · PowerProfile", nominalCapacity(context)?.let { "$it mAh" } ?: context.getString(R.string.battery_unavailable))
        capacity += Row("${context.getString(R.string.battery_lbl_design)} · charge_full_design", snap(snap, BatteryInfoChannel.SLOT_DESIGN_CAPACITY)
            ?: sysfsInt("$BATTERY_SYSFS/charge_full_design")?.let { BatteryInfoChannel.mah(it) } ?: context.getString(R.string.battery_unavailable))
        capacity += Row("${context.getString(R.string.battery_lbl_fg1_design)} · fg1_design_capacity", snap(snap, BatteryInfoChannel.SLOT_FG1_DESIGN) ?: context.getString(R.string.battery_unavailable))
        if (dual) {
            capacity += Row("${context.getString(R.string.battery_lbl_fg2_design)} · fg2_design_capacity", snap(snap, BatteryInfoChannel.SLOT_FG2_DESIGN) ?: context.getString(R.string.battery_unavailable))
            capacity += Row("${context.getString(R.string.battery_lbl_fg2_rm)} · fg2_rm", snap(snap, BatteryInfoChannel.SLOT_FG2_RM) ?: context.getString(R.string.battery_unavailable))
        }
        capacity += Row("${context.getString(R.string.battery_lbl_fg1_rm)} · fg1_rm", snap(snap, BatteryInfoChannel.SLOT_FG1_RM) ?: context.getString(R.string.battery_unavailable))
        capacity += Row("${context.getString(R.string.battery_lbl_full)} · charge_full", snap(snap, BatteryInfoChannel.SLOT_FCC)
            ?: sysfsInt("$BATTERY_SYSFS/charge_full")?.let { BatteryInfoChannel.mah(it) } ?: context.getString(R.string.battery_unavailable))
        capacity += Row("${context.getString(R.string.battery_lbl_charge_counter)} · charge_counter", BatteryInfoChannel.mah(intProperty(context, 1)) ?: context.getString(R.string.battery_unavailable))
        capacity += Row("${context.getString(R.string.battery_lbl_current)} · current_now", BatteryInfoChannel.ma(intProperty(context, 2)) ?: context.getString(R.string.battery_unavailable))
        sections += Section(context.getString(R.string.battery_sec_capacity), capacity)

        // 健康与寿命
        val health = mutableListOf<Row>()
        val soh = snap(snap, BatteryInfoChannel.SLOT_BATTERY_SOH)
        health += Row("${context.getString(R.string.battery_lbl_soh)} · getBatterySoh", soh ?: context.getString(R.string.battery_unavailable))
        health += Row("${context.getString(R.string.battery_lbl_fg1_soh)} · fg1_soh", snap(snap, BatteryInfoChannel.SLOT_FG1_SOH) ?: context.getString(R.string.battery_unavailable))
        if (dual) health += Row("${context.getString(R.string.battery_lbl_fg2_soh)} · fg2_soh", snap(snap, BatteryInfoChannel.SLOT_FG2_SOH) ?: context.getString(R.string.battery_unavailable))
        health += Row("${context.getString(R.string.battery_lbl_cycle)} · cycle_count", snap(snap, BatteryInfoChannel.SLOT_CYCLE_COUNT) ?: context.getString(R.string.battery_unavailable))
        health += Row("${context.getString(R.string.battery_lbl_fg1_cycle)} · fg1_cycle", snap(snap, BatteryInfoChannel.SLOT_FG1_CYCLE) ?: context.getString(R.string.battery_unavailable))
        if (dual) health += Row("${context.getString(R.string.battery_lbl_fg2_cycle)} · fg2_cycle", snap(snap, BatteryInfoChannel.SLOT_FG2_CYCLE) ?: context.getString(R.string.battery_unavailable))
        val fg1Soh = digits(snap(snap, BatteryInfoChannel.SLOT_FG1_SOH))
        if (fg1Soh != null) health += Row("${context.getString(R.string.battery_lbl_fg1_rating)} · 评级", sohRating(context, fg1Soh))
        val cycleCount = digits(snap(snap, BatteryInfoChannel.SLOT_CYCLE_COUNT))
        if (soh == null && cycleCount != null) {
            health += Row("${context.getString(R.string.battery_lbl_est_health)} · cycle", "${(100 - cycleCount / 20).coerceIn(0, 100)} %")
        }
        sections += Section(context.getString(R.string.battery_sec_health), health)

        // 标识
        val identity = mutableListOf<Row>()
        identity += Row("${context.getString(R.string.battery_lbl_model)} · model_name", snap(snap, BatteryInfoChannel.SLOT_MODEL_NAME) ?: context.getString(R.string.battery_unavailable))
        identity += Row("${context.getString(R.string.battery_lbl_serial)} · serial_number",
            snap(snap, BatteryInfoChannel.SLOT_SERIAL_NUMBER) ?: snap(snap, BatteryInfoChannel.SLOT_SOH_SN) ?: context.getString(R.string.battery_unavailable))
        val batteryNum = snap(snap, BatteryInfoChannel.SLOT_BATTERY_NUM)
        identity += Row("${context.getString(R.string.battery_lbl_num)} · battery_num", when (batteryNum) {
            null -> context.getString(R.string.battery_unavailable)
            "1" -> context.getString(R.string.battery_num_dual)
            else -> context.getString(R.string.battery_num_single_fmt, batteryNum)
        })
        identity += Row("${context.getString(R.string.battery_lbl_soh_sn)} · soh_sn", snap(snap, BatteryInfoChannel.SLOT_SOH_SN) ?: context.getString(R.string.battery_unavailable))
        if (dual) identity += Row("${context.getString(R.string.battery_lbl_fg2_soh_sn)} · fg2_soh_sn", snap(snap, BatteryInfoChannel.SLOT_FG2_SOH_SN) ?: context.getString(R.string.battery_unavailable))
        identity += Row("${context.getString(R.string.battery_lbl_authentic)} · authentic", snap(snap, BatteryInfoChannel.SLOT_AUTHENTIC) ?: context.getString(R.string.battery_unavailable))
        if (dual) identity += Row("${context.getString(R.string.battery_lbl_slave_authentic)} · slave_authentic", snap(snap, BatteryInfoChannel.SLOT_SLAVE_AUTHENTIC) ?: context.getString(R.string.battery_unavailable))
        identity += Row("${context.getString(R.string.battery_lbl_manufacturing)} · manufacturing_date", snap(snap, BatteryInfoChannel.SLOT_MANUFACTURING_DATE) ?: context.getString(R.string.battery_unavailable))
        identity += Row("${context.getString(R.string.battery_lbl_first_usage)} · first_usage_date", snap(snap, BatteryInfoChannel.SLOT_FIRST_USAGE_DATE) ?: context.getString(R.string.battery_unavailable))
        identity += Row("${context.getString(R.string.battery_lbl_pd_auth)} · pd_authentication", snap(snap, BatteryInfoChannel.SLOT_PD_AUTH) ?: context.getString(R.string.battery_unavailable))
        sections += Section(context.getString(R.string.battery_sec_identity), identity)

        // 充电
        val charging = mutableListOf<Row>()
        // MAX charging power is only shown when the HAL reports it (some devices report 0).
        snap(snap, BatteryInfoChannel.SLOT_CHARGE_POWER)?.let {
            charging += Row("${context.getString(R.string.battery_lbl_chg_power)} · charging_power", it)
        }
        liveChargePower(context, intent, charging)
        charging += Row("${context.getString(R.string.battery_lbl_chg_type)} · charge_type", snap(snap, BatteryInfoChannel.SLOT_CHARGE_TYPE) ?: context.getString(R.string.battery_unavailable))
        charging += Row("${context.getString(R.string.battery_lbl_ibat)} · ibat", snap(snap, BatteryInfoChannel.SLOT_IBAT) ?: context.getString(R.string.battery_unavailable))
        charging += Row("${context.getString(R.string.battery_lbl_board_temp)} · board_temp", sysfsInt("$THERMAL_SYSFS/board_sensor_temp")?.let { "${it / 1000.0} °C" } ?: context.getString(R.string.battery_unavailable))
        sections += Section(context.getString(R.string.battery_sec_charging), charging)

        return sections.filter { it.rows.isNotEmpty() }
    }

    private fun sysfsRaw(path: String): String? =
        runCatching { File(path).readText().trim().ifEmpty { null } }.getOrNull()

    private fun sysfsInt(path: String): Int? = sysfsRaw(path)?.toIntOrNull()

    /** Device nominal capacity from [com.android.internal.os.PowerProfile#getBatteryCapacity]. */
    private fun nominalCapacity(context: Context): Int? = runCatching {
        val profile = Class.forName("com.android.internal.os.PowerProfile")
            .getConstructor(Context::class.java).newInstance(context)
        val method = profile.javaClass.getMethod("getBatteryCapacity")
        (method.invoke(profile) as? Number)?.toInt()
    }.getOrNull()
}
