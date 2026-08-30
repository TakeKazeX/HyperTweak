package com.takekazex.hypertweak.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
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
 * snapshot does not carry, and that a normal process cannot read, shows "不可用".
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

    private fun pluggedLabel(value: Int): String = when (value) {
        0 -> "未充电"
        1 -> "AC 充电"
        2 -> "USB 充电"
        4 -> "无线充电"
        8 -> "无线反充"
        else -> "充电 (类型 $value)"
    }

    private fun healthLabel(value: Int): String = when (value) {
        1 -> "未知"
        2 -> "良好"
        3 -> "过热"
        4 -> "损坏"
        5 -> "过压"
        6 -> "未指定"
        7 -> "过冷"
        else -> "未知"
    }

    private fun statusLabel(value: Int): String = when (value) {
        1 -> "未知"
        2 -> "充电中"
        3 -> "放电中"
        4 -> "未充电"
        5 -> "已充满"
        else -> "未知"
    }

    private fun halfCelsius(tenths: Int?): String =
        if (tenths == null || tenths == Int.MIN_VALUE || tenths <= -500) "不可用" else "${tenths / 10.0} °C"

    private fun milliVolt(mv: Int?): String =
        if (mv == null || mv <= 0) "不可用" else "${mv / 1000.0} V"

    private fun sohRating(sohPercent: Int?): String = when {
        sohPercent == null || sohPercent < 0 -> "无法读取"
        sohPercent >= 90 -> "优"
        sohPercent >= 80 -> "良"
        sohPercent >= 60 -> "一般"
        else -> "需更换"
    }

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
        out += Row("实时充电功率 · V×I", String.format(Locale.US, "%.1f W", watt))
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
        basic += Row("电量百分比 · level/scale", if (percent in 0..100) "$percent %" else "不可用")
        basic += Row("电池状态 · status", statusLabel(intent?.getIntExtra("status", -1) ?: -1))
        basic += Row("充电状态 · plugged", pluggedLabel(intent?.getIntExtra("plugged", 0) ?: 0))
        basic += Row("电池健康 · health", healthLabel(intent?.getIntExtra("health", -1) ?: -1))
        val temp = intent?.getIntExtra("temperature", Int.MIN_VALUE) ?: sysfsInt("$BATTERY_SYSFS/temperature")?.let { it * 10 }
        basic += Row("电池温度 · temperature", halfCelsius(temp))
        val voltage = intent?.getIntExtra("voltage", Int.MIN_VALUE) ?: sysfsInt("$BATTERY_SYSFS/voltage_now")?.let { it / 1000 }
        basic += Row("电池电压 · voltage", milliVolt(voltage))
        basic += Row("电池技术 · technology", intent?.getStringExtra("technology") ?: sysfsRaw("$BATTERY_SYSFS/technology") ?: "不可用")
        sections += Section("基础信息", basic)

        // 容量 (mix of snapshot + framework)
        val capacity = mutableListOf<Row>()
        capacity += Row("设备标称容量 · PowerProfile", nominalCapacity(context)?.let { "$it mAh" } ?: "不可用")
        capacity += Row("设计容量 · charge_full_design", snap(snap, BatteryInfoChannel.SLOT_DESIGN_CAPACITY)
            ?: sysfsInt("$BATTERY_SYSFS/charge_full_design")?.let { BatteryInfoChannel.mah(it) } ?: "不可用")
        capacity += Row("主电池设计容量 · fg1_design_capacity", snap(snap, BatteryInfoChannel.SLOT_FG1_DESIGN) ?: "不可用")
        if (dual) {
            capacity += Row("副电池设计容量 · fg2_design_capacity", snap(snap, BatteryInfoChannel.SLOT_FG2_DESIGN) ?: "不可用")
            capacity += Row("副电池剩余容量 · fg2_rm", snap(snap, BatteryInfoChannel.SLOT_FG2_RM) ?: "不可用")
        }
        capacity += Row("主电池剩余容量 · fg1_rm", snap(snap, BatteryInfoChannel.SLOT_FG1_RM) ?: "不可用")
        capacity += Row("满充容量 · charge_full", snap(snap, BatteryInfoChannel.SLOT_FCC)
            ?: sysfsInt("$BATTERY_SYSFS/charge_full")?.let { BatteryInfoChannel.mah(it) } ?: "不可用")
        capacity += Row("电量计数 · charge_counter", BatteryInfoChannel.mah(intProperty(context, 1)) ?: "不可用")
        capacity += Row("电池电流 · current_now", BatteryInfoChannel.ma(intProperty(context, 2)) ?: "不可用")
        sections += Section("容量", capacity)

        // 健康与寿命
        val health = mutableListOf<Row>()
        val soh = snap(snap, BatteryInfoChannel.SLOT_BATTERY_SOH)
        health += Row("电池健康度 · getBatterySoh", soh ?: "不可用")
        health += Row("主电池健康度 · fg1_soh", snap(snap, BatteryInfoChannel.SLOT_FG1_SOH) ?: "不可用")
        if (dual) health += Row("副电池健康度 · fg2_soh", snap(snap, BatteryInfoChannel.SLOT_FG2_SOH) ?: "不可用")
        health += Row("循环次数 · cycle_count", snap(snap, BatteryInfoChannel.SLOT_CYCLE_COUNT) ?: "不可用")
        health += Row("主电池循环 · fg1_cycle", snap(snap, BatteryInfoChannel.SLOT_FG1_CYCLE) ?: "不可用")
        if (dual) health += Row("副电池循环 · fg2_cycle", snap(snap, BatteryInfoChannel.SLOT_FG2_CYCLE) ?: "不可用")
        val fg1Soh = snap(snap, BatteryInfoChannel.SLOT_FG1_SOH)?.removeSuffix(" %")?.toIntOrNull()
        if (fg1Soh != null) health += Row("主电池健康等级 · 评级", sohRating(fg1Soh))
        val cycleStr = snap(snap, BatteryInfoChannel.SLOT_CYCLE_COUNT)
        if (soh == null && cycleStr != null) {
            val n = cycleStr.removeSuffix(" 次").toIntOrNull()
            if (n != null) health += Row("估算健康度 (按循环) · cycle", "${(100 - n / 20).coerceIn(0, 100)} %")
        }
        sections += Section("健康与寿命", health)

        // 标识
        val identity = mutableListOf<Row>()
        identity += Row("电池型号 · model_name", snap(snap, BatteryInfoChannel.SLOT_MODEL_NAME) ?: "不可用")
        identity += Row("电池序列号 · serial_number",
            snap(snap, BatteryInfoChannel.SLOT_SERIAL_NUMBER) ?: snap(snap, BatteryInfoChannel.SLOT_SOH_SN) ?: "不可用")
        val batteryNum = snap(snap, BatteryInfoChannel.SLOT_BATTERY_NUM)
        identity += Row("电池编号 · battery_num", when (batteryNum) {
            null -> "不可用"
            "1" -> "1 (双电池)"
            else -> "$batteryNum (单电池)"
        })
        identity += Row("主电池序列号 · soh_sn", snap(snap, BatteryInfoChannel.SLOT_SOH_SN) ?: "不可用")
        if (dual) identity += Row("副电池序列号 · fg2_soh_sn", snap(snap, BatteryInfoChannel.SLOT_FG2_SOH_SN) ?: "不可用")
        identity += Row("主电池是否原装 · authentic", snap(snap, BatteryInfoChannel.SLOT_AUTHENTIC) ?: "不可用")
        if (dual) identity += Row("副电池是否原装 · slave_authentic", snap(snap, BatteryInfoChannel.SLOT_SLAVE_AUTHENTIC) ?: "不可用")
        identity += Row("生产日期 · manufacturing_date", snap(snap, BatteryInfoChannel.SLOT_MANUFACTURING_DATE) ?: "不可用")
        identity += Row("首次使用日期 · first_usage_date", snap(snap, BatteryInfoChannel.SLOT_FIRST_USAGE_DATE) ?: "不可用")
        identity += Row("PD 认证 · pd_authentication", snap(snap, BatteryInfoChannel.SLOT_PD_AUTH) ?: "不可用")
        sections += Section("标识", identity)

        // 充电
        val charging = mutableListOf<Row>()
        // MAX charging power is only shown when the HAL reports it (some devices report 0).
        snap(snap, BatteryInfoChannel.SLOT_CHARGE_POWER)?.let {
            charging += Row("最大充电功率 · charging_power", it)
        }
        liveChargePower(context, intent, charging)
        charging += Row("充电类型 · charge_type", snap(snap, BatteryInfoChannel.SLOT_CHARGE_TYPE) ?: "不可用")
        charging += Row("电池电流 (HAL) · ibat", snap(snap, BatteryInfoChannel.SLOT_IBAT) ?: "不可用")
        charging += Row("主板温度 · board_temp", sysfsInt("$THERMAL_SYSFS/board_sensor_temp")?.let { "${it / 1000.0} °C" } ?: "不可用")
        sections += Section("充电", charging)

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
