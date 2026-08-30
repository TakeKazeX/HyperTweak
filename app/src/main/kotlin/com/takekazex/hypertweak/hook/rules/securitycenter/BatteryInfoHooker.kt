package com.takekazex.hypertweak.hook.rules.securitycenter

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.BatteryInfoChannel
import com.takekazex.hypertweak.util.DebugLog
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed
import java.io.File
import java.lang.reflect.Method
import java.util.Locale

/**
 * Reads the battery parameters the module's own process cannot reach (design capacity,
 * manufacturing/`first_usage_date`, SOH, cycle count, `authentic`, model/serial, and the whole
 * `miui.util.IMiCharge` surface) inside `com.miui.securitycenter`, which runs as `android.uid.system`
 * and therefore has the same access the stock battery page uses. It pushes a formatted snapshot to
 * the module's [com.takekazex.hypertweak.BatteryInfoProvider] on a slow timer; the module page reads
 * it back. See `util/BatteryInfoReader` for the always-available basic tier.
 *
 * No target class is hooked — the hooker just runs in the privileged process and publishes proactively.
 */
object BatteryInfoHooker : StaticHooker() {
    private const val TAG = "BatteryInfoHooker"

    override val hotReloadMode = HotReloadMode.RECREATE

    private const val PUBLISH_INTERVAL_MS = 1_000L

    private const val BATTERY_SYSFS = "/sys/class/power_supply/battery"
    private const val QCOM_SYSFS = "/sys/class/qcom-battery"

    @Volatile
    private var publishGeneration = 0

    // ─── IMiCharge reflection (cached) ───────────────────────────────────────

    private var imiChargeClass: Class<*>? = null
    private var imiChargeInstance: Any? = null
    private var miChargePathMethod: Method? = null
    private val imiChargeMethods = mutableMapOf<String, Method?>()
    private var imiChargeResolved = false

    private fun resolveIMiCharge(): Boolean {
        if (imiChargeResolved) return imiChargeInstance != null
        imiChargeResolved = true
        runCatching {
            val cls = Class.forName("miui.util.IMiCharge")
            imiChargeClass = cls
            imiChargeInstance = cls.getMethod("getInstance").invoke(null)
            miChargePathMethod = cls.getMethod("getMiChargePath", String::class.java)
        }
        return imiChargeInstance != null
    }

    private fun path(key: String): String? {
        if (!resolveIMiCharge()) return null
        val m = miChargePathMethod ?: return null
        return runCatching { m.invoke(imiChargeInstance, key) as? String }.getOrNull()
    }

    private fun call(name: String): String? {
        if (!resolveIMiCharge()) return null
        val m = imiChargeMethods.getOrPut(name) {
            runCatching { imiChargeClass!!.getMethod(name) }.getOrNull() ?: return null
        } ?: return null
        return runCatching { m.invoke(imiChargeInstance)?.toString() }.getOrNull()
    }

    private fun pathInt(key: String): Int? = path(key)?.toIntOrNull()
    private fun callInt(name: String): Int? = call(name)?.toIntOrNull()

    // ─── sysfs ───────────────────────────────────────────────────────────────

    private fun sysfsRaw(p: String): String? = runCatching {
        File(p).readText().trim().ifEmpty { null }
    }.getOrNull()

    private fun sysfsInt(p: String): Int? = sysfsRaw(p)?.toIntOrNull()

    // ─── localized value words ───────────────────────────────────────────────
    //
    // The module's own resources are not backed by a module context here (the hooker runs inside
    // com.miui.securitycenter), so the few value words the snapshot carries (cycle unit, authentic
    // yes/no, PD-auth state) are picked from the selected app language directly. The explicit
    // preference (KEY_LANGUAGE: 1 = 中文, 2 = English) wins; "device default" follows Locale.

    private fun isZh(): Boolean = when (Preferences.getInt(Preferences.KEY_LANGUAGE, 0)) {
        1 -> true
        2 -> false
        else -> Locale.getDefault().language == "zh"
    }

    private fun cycleValue(n: Int): String = if (isZh()) "$n 次" else "$n cycles"

    private fun yesNo(value: Boolean): String = when {
        !isZh() -> if (value) "Yes" else "No"
        value -> "是"
        else -> "否"
    }

    private fun pdAuth(value: String?): String? = when (value) {
        "1" -> if (isZh()) "已认证" else "Authenticated"
        "0" -> if (isZh()) "未认证" else "Not authenticated"
        "" -> null
        else -> value
    }

    override fun onPrepareHotReload() {
        publishGeneration++
        imiChargeResolved = false
        imiChargeInstance = null
        miChargePathMethod = null
        imiChargeMethods.clear()
    }

    override fun onHook() {
        val context = resolveAppContext() ?: run {
            DebugLog.hookSkipped(TAG, "publish", "no app context")
            return
        }
        publishGeneration++
        publishOnce(context)
        schedulePublish(context, publishGeneration)
        DebugLog.d(TAG, "battery-info publisher started")
    }

    /**
     * The `ModuleContext.appContext` is null for package hooks, so resolve the process application
     * through `ActivityThread.currentApplication()` (the classic LSPosed route), falling back to the
     * ezxhook app context.
     */
    private fun resolveAppContext(): Context? {
        hookParam.appContext?.let { return it }
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            return (at.getDeclaredMethod("currentApplication").invoke(null) as? Context)
        }
        return runCatching { EzXposed.appContextOrNull }.getOrNull()
    }

    private fun schedulePublish(context: Context, gen: Int) {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (gen != publishGeneration) return
                try {
                    publishOnce(context)
                } catch (_: Throwable) {
                } finally {
                    if (gen == publishGeneration) handler.postDelayed(this, PUBLISH_INTERVAL_MS)
                }
            }
        }
        handler.postDelayed(runnable, PUBLISH_INTERVAL_MS)
    }

    private fun publishOnce(context: Context) {
        val bundle = collect(context)
        if (bundle.isEmpty) return
        runCatching {
            context.contentResolver.call(
                BatteryInfoChannel.uri(),
                BatteryInfoChannel.METHOD_SET,
                null,
                bundle
            )
        }.onFailure {
            DebugLog.w(TAG, "push battery snapshot failed", it)
        }
    }

    private fun collect(context: Context): Bundle {
        val b = Bundle()
        put(b, BatteryInfoChannel.SLOT_DESIGN_CAPACITY, designCapacity())
        put(b, BatteryInfoChannel.SLOT_CYCLE_COUNT, cycleCount())
        put(b, BatteryInfoChannel.SLOT_MODEL_NAME, sysfsRaw("$BATTERY_SYSFS/model_name"))
        put(b, BatteryInfoChannel.SLOT_SERIAL_NUMBER,
            sysfsRaw("$BATTERY_SYSFS/serial_number") ?: path("soh_sn"))
        put(b, BatteryInfoChannel.SLOT_FG1_DESIGN, BatteryInfoChannel.mah(path("fg1_design_capacity")?.toDoubleOrNull()))
        put(b, BatteryInfoChannel.SLOT_FG2_DESIGN, BatteryInfoChannel.mah(path("fg2_design_capacity")?.toDoubleOrNull()))
        put(b, BatteryInfoChannel.SLOT_FG1_RM, BatteryInfoChannel.mah(pathInt("fg1_rm")))
        put(b, BatteryInfoChannel.SLOT_FG2_RM, BatteryInfoChannel.mah(pathInt("fg2_rm")))
        put(b, BatteryInfoChannel.SLOT_FG1_SOH, pathInt("fg1_soh")?.let { "$it %" })
        put(b, BatteryInfoChannel.SLOT_FG2_SOH, pathInt("fg2_soh")?.let { "$it %" })
        put(b, BatteryInfoChannel.SLOT_FG1_CYCLE, pathInt("fg1_cycle")?.let(::cycleValue))
        put(b, BatteryInfoChannel.SLOT_FG2_CYCLE, pathInt("fg2_cycle")?.let(::cycleValue))
        put(b, BatteryInfoChannel.SLOT_BATTERY_SOH, call("getBatterySoh")?.takeUnless { it == "error" }?.let { "$it %" })
        put(b, BatteryInfoChannel.SLOT_BATTERY_CYCLE, call("getBatteryCycleCount")?.takeUnless { it == "error" }?.let { v -> v.toIntOrNull()?.let(::cycleValue) ?: v })
        put(b, BatteryInfoChannel.SLOT_MANUFACTURING_DATE, BatteryInfoChannel.date(path("manufacturing_date")))
        put(b, BatteryInfoChannel.SLOT_FIRST_USAGE_DATE, BatteryInfoChannel.date(path("first_usage_date")))
        put(b, BatteryInfoChannel.SLOT_BATTERY_NUM, path("battery_num"))
        put(b, BatteryInfoChannel.SLOT_SOH_SN, path("soh_sn"))
        put(b, BatteryInfoChannel.SLOT_FG2_SOH_SN, path("fg2_soh_sn"))
        put(b, BatteryInfoChannel.SLOT_AUTHENTIC, pathInt("authentic")?.let { yesNo(it == 1) })
        put(b, BatteryInfoChannel.SLOT_SLAVE_AUTHENTIC, pathInt("slave_authentic")?.let { yesNo(it == 1) })
        put(b, BatteryInfoChannel.SLOT_CHARGE_TYPE, (call("getBatteryChargeType")
            ?: sysfsRaw("/sys/class/power_supply/usb/type")
            ?: sysfsRaw("$QCOM_SYSFS/real_type")))
        put(b, BatteryInfoChannel.SLOT_CHARGE_POWER, call("getChargingPowerMax")?.toIntOrNull()?.takeIf { it > 0 }?.let { "$it W" })
        put(b, BatteryInfoChannel.SLOT_IBAT, BatteryInfoChannel.ma(callInt("getBatteryIbat")))
        put(b, BatteryInfoChannel.SLOT_FCC, BatteryInfoChannel.mah(callInt("getBatteryChargeFull")))
        put(b, BatteryInfoChannel.SLOT_PD_AUTH, call("getPdAuthentication")?.let(::pdAuth))
        return b
    }

    private fun designCapacity(): String? {
        val sys = sysfsInt("$BATTERY_SYSFS/charge_full_design")
        if (sys != null) return BatteryInfoChannel.mah(sys)
        val hal = pathInt("charge_full_design")
        if (hal != null) return BatteryInfoChannel.mah(hal)
        return null
    }

    private fun cycleCount(): String? {
        val sys = sysfsInt("$BATTERY_SYSFS/cycle_count")
        if (sys != null) return cycleValue(sys)
        val hal = call("getBatteryCycleCount")?.takeUnless { it == "error" }
        if (hal != null) return hal.toIntOrNull()?.let(::cycleValue) ?: hal
        return null
    }

    private fun put(bundle: Bundle, key: String, value: String?) {
        if (!value.isNullOrBlank()) bundle.putString(key, value)
    }
}
