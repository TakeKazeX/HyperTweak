@file:Suppress("StaticFieldLeak")

package com.takekazex.hypertweak.hook.rules.securitycenter

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.text.format.DateFormat
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.CompatibleMethodResolver
import com.takekazex.hypertweak.hook.base.DexKitManager
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.lingqiqi5211.ezhooktool.core.callMethodOrNull
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the native battery-protection rows, explicitly fills temperature/cycle values, and adds
 * design/full-charge capacity rows.
 *
 * The current Security Center 13.2.7 handler is
 * `ChargeProtectFragment$c`; older references used `$d`, so the handler is resolved explicitly
 * from the current class shape and the four battery readers are resolved by their stable string
 * keys with a narrow current-build fallback.
 */
@SuppressLint("StaticFieldLeak")
object MoreBatteryInfoHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "MoreBatteryInfo"
    private const val PACKAGE = "com.miui.securitycenter"
    private const val CHARGE_PROTECT_FRAGMENT =
        "com.miui.powercenter.nightcharge.ChargeProtectFragment"
    private const val CHARGE_PROTECT_HANDLER =
        "com.miui.powercenter.nightcharge.ChargeProtectFragment\$c"
    private const val MSG_REFRESH_BATTERY_INFO = 1

    private const val BATTERY_INFO_CATEGORY = "preference_key_category_battery_info"
    private const val HEALTH_KEY = "reference_battery_health"
    private const val CURRENT_TEMP_KEY = "reference_current_temp"
    private const val TODAY_CHARGE_KEY = "reference_toady_charge_time"
    private const val CYCLE_COUNT_KEY = "reference_cycle_count"
    private const val PRODUCTION_DATE_KEY = "reference_production_date"
    private const val FIRST_USE_DATE_KEY = "reference_first_use_date"
    private const val DESIGN_CAPACITY_KEY = "reference_battery_design_capacity"
    private const val FULL_CAPACITY_KEY = "reference_battery_full_capacity"

    private const val BATTERY_PROPERTY_MANUFACTURING_DATE = 7
    private const val BATTERY_PROPERTY_FIRST_USAGE_DATE = 8
    private const val DATE_FORMAT = "yyyyMMdd"
    private const val EMPTY_VALUE = "--"

    private val KEEP_KEYS = setOf(
        HEALTH_KEY,
        CURRENT_TEMP_KEY,
        TODAY_CHARGE_KEY,
        CYCLE_COUNT_KEY,
        PRODUCTION_DATE_KEY,
        FIRST_USE_DATE_KEY,
        DESIGN_CAPACITY_KEY,
        FULL_CAPACITY_KEY
    )
    private val DESIGN_CAPACITY_PATHS = listOf(
        "/sys/class/power_supply/battery/charge_full_design",
        "/sys/class/power_supply/bms/charge_full_design"
    )
    private val FULL_CAPACITY_PATHS = listOf(
        "/sys/class/power_supply/battery/charge_full",
        "/sys/class/power_supply/bms/charge_full"
    )
    private val PRODUCTION_DATE_PATHS = listOf(
        "/sys/class/power_supply/battery/manufacturing_date",
        "/sys/class/power_supply/battery/production_date",
        "/sys/class/power_supply/bms/manufacturing_date"
    )
    private val FIRST_USE_DATE_PATHS = listOf(
        "/sys/class/power_supply/battery/first_usage_date",
        "/sys/class/power_supply/bms/first_usage_date"
    )
    private val TEMPERATURE_PATHS = listOf(
        "/sys/class/power_supply/battery/temp",
        "/sys/class/power_supply/bms/temp"
    )
    private val CYCLE_COUNT_PATHS = listOf(
        "/sys/class/power_supply/battery/cycle_count",
        "/sys/class/power_supply/bms/cycle_count"
    )

    private var batteryInfoCategory: Any? = null
    private var currentTemperaturePreference: Any? = null
    private var cycleCountPreference: Any? = null
    private var productionDatePreference: Any? = null
    private var firstUseDatePreference: Any? = null
    private var designCapacityPreference: Any? = null
    private var fullCapacityPreference: Any? = null
    private var batteryContext: Context? = null

    override fun onPrepareHotReload() {
        clearCapturedPreferences()
    }

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        if (!Preferences.getBoolean(Preferences.KEY_SECURITY_CENTER_MORE_BATTERY_INFO, false)) {
            DebugLog.hookSkipped(TAG, "more battery-protection information", "disabled")
            return
        }

        readMethods = resolveReadMethods(hookParam.appInfo?.sourceDir)
        hookPreferenceRemoval()
        hookChargeProtectFragment()
        hookChargeProtectHandler()
    }

    private var readMethods = BatteryReadMethods()

    private fun hookPreferenceRemoval() {
        val groupClass = "androidx.preference.PreferenceGroup".toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "PreferenceGroup.removePreference", "class not found")
            return
        }
        val preferenceClass = "androidx.preference.Preference".toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, "PreferenceGroup.removePreference", "Preference class not found")
            return
        }
        val removePreference = groupClass.declaredMethods.singleOrNull { method ->
            method.name == "removePreference" &&
                method.parameterTypes.contentEquals(arrayOf(preferenceClass))
        } ?: run {
            DebugLog.hookSkipped(TAG, "PreferenceGroup.removePreference", "method not found")
            return
        }

        runCatching {
            removePreference.isAccessible = true
            deoptimize(removePreference)
            removePreference.hook("more_battery_info_keep_rows") {
                before { param ->
                    HookFailurePolicy.open(TAG, "removePreference.before", Unit) {
                        val preference = param.args.getOrNull(0) ?: return@open
                        val key = preference.callMethodOrNull("getKey") as? String ?: return@open
                        if (key in KEEP_KEYS) param.result = false
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, removePreference.toGenericString(), it)
        }
    }

    private fun hookChargeProtectFragment() {
        val fragmentClass = CHARGE_PROTECT_FRAGMENT.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, CHARGE_PROTECT_FRAGMENT, "class not found")
            return
        }
        val onCreatePreferences = CompatibleMethodResolver.find(
            fragmentClass,
            "onCreatePreferences",
            parameterTypes = listOf(Bundle::class.java, String::class.java)
        ) ?: run {
            DebugLog.hookSkipped(TAG, "$CHARGE_PROTECT_FRAGMENT#onCreatePreferences", "method not found")
            return
        }

        runCatching {
            onCreatePreferences.hook("more_battery_info_capture") {
                after { param ->
                    HookFailurePolicy.open(TAG, "onCreatePreferences.after", Unit) {
                        capturePreferences(param.thisObject)
                        refreshBatteryRows()
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, onCreatePreferences.toGenericString(), it)
        }
    }

    private fun hookChargeProtectHandler() {
        val handlerClass = CHARGE_PROTECT_HANDLER.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, CHARGE_PROTECT_HANDLER, "class not found")
            return
        }
        val handleMessage = CompatibleMethodResolver.find(
            handlerClass,
            "handleMessage",
            parameterTypes = listOf(Message::class.java)
        ) ?: run {
            DebugLog.hookSkipped(TAG, "$CHARGE_PROTECT_HANDLER#handleMessage", "method not found")
            return
        }

        runCatching {
            handleMessage.hook("more_battery_info_refresh") {
                after { param ->
                    HookFailurePolicy.open(TAG, "handleMessage.after", Unit) {
                        val message = param.args.getOrNull(0) as? Message ?: return@open
                        if (message.what == MSG_REFRESH_BATTERY_INFO) refreshBatteryRows()
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, handleMessage.toGenericString(), it)
        }
    }

    private fun capturePreferences(fragment: Any?) {
        if (fragment == null) {
            clearCapturedPreferences()
            return
        }
        batteryContext = fragment.callMethodOrNull("getContext") as? Context
        batteryInfoCategory = findPreference(fragment, BATTERY_INFO_CATEGORY)
        currentTemperaturePreference = findPreference(fragment, CURRENT_TEMP_KEY)
        cycleCountPreference = findPreference(fragment, CYCLE_COUNT_KEY)
        productionDatePreference = findPreference(fragment, PRODUCTION_DATE_KEY)
        firstUseDatePreference = findPreference(fragment, FIRST_USE_DATE_KEY)
        designCapacityPreference = findPreference(fragment, DESIGN_CAPACITY_KEY)
        fullCapacityPreference = findPreference(fragment, FULL_CAPACITY_KEY)
    }

    private fun findPreference(fragment: Any, key: String): Any? =
        fragment.callMethodOrNull("findPreference", key)

    private fun refreshBatteryRows() {
        val context = batteryContext ?: return
        ensureDynamicPreferences(context)

        updateDatePreference(
            productionDatePreference,
            resolveBatteryDateText(context, readMethods.manufacturingDate, BATTERY_PROPERTY_MANUFACTURING_DATE, PRODUCTION_DATE_PATHS)
        )
        updateDatePreference(
            firstUseDatePreference,
            resolveBatteryDateText(context, readMethods.firstUsageDate, BATTERY_PROPERTY_FIRST_USAGE_DATE, FIRST_USE_DATE_PATHS)
        )
        updateValuePreference(
            currentTemperaturePreference,
            resolveBatteryTemperatureText(context) ?: EMPTY_VALUE
        )
        updateValuePreference(
            cycleCountPreference,
            resolveCycleCountText() ?: EMPTY_VALUE
        )
        setPreferenceText(
            designCapacityPreference,
            resolveCapacityText(readMethods.designCapacity, DESIGN_CAPACITY_PATHS)
        )
        setPreferenceText(
            fullCapacityPreference,
            resolveCapacityText(readMethods.fullCapacity, FULL_CAPACITY_PATHS)
        )
    }

    private fun ensureDynamicPreferences(context: Context) {
        val category = batteryInfoCategory ?: return
        val nextOrder = resolveNextPreferenceOrder(category)
        if (designCapacityPreference == null) {
            designCapacityPreference = newTextPreference(
                category,
                context,
                DESIGN_CAPACITY_KEY,
                localizedTitle("设计容量", "Design capacity"),
                nextOrder
            )
        }
        if (fullCapacityPreference == null) {
            fullCapacityPreference = newTextPreference(
                category,
                context,
                FULL_CAPACITY_KEY,
                localizedTitle("满充容量", "Full-charge capacity"),
                nextOrder + 1
            )
        }
    }

    private fun newTextPreference(
        category: Any,
        context: Context,
        key: String,
        title: String,
        order: Int
    ): Any? {
        val preferenceClass = "miuix.preference.TextPreference".toClassOrNull() ?: return null
        val constructor = preferenceClass.declaredConstructors.firstOrNull { ctor ->
            ctor.parameterTypes.contentEquals(arrayOf(Context::class.java))
        } ?: return null
        return runCatching {
            constructor.isAccessible = true
            val preference = constructor.newInstance(context)
            preference.callMethodOrNull("setKey", key)
            preference.callMethodOrNull("setTitle", title)
            preference.callMethodOrNull("setClickable", false)
            preference.callMethodOrNull("setTouchAnimationEnable", false)
            preference.callMethodOrNull("setOrder", order)
            category.callMethodOrNull("addPreference", preference)
            preference
        }.onFailure {
            DebugLog.w(TAG, "failed to create dynamic preference $key", it)
        }.getOrNull()
    }

    private fun resolveNextPreferenceOrder(category: Any): Int {
        val count = category.callMethodOrNull("getPreferenceCount") as? Int ?: return 1000
        var maxOrder = Int.MIN_VALUE
        for (index in 0 until count) {
            val preference = category.callMethodOrNull("getPreference", index) ?: continue
            val order = preference.callMethodOrNull("getOrder") as? Int ?: continue
            maxOrder = maxOf(maxOrder, order)
        }
        return when {
            maxOrder == Int.MIN_VALUE -> count + 100
            maxOrder >= Int.MAX_VALUE - 2 -> Int.MAX_VALUE - 2
            else -> maxOrder + 1
        }
    }

    private fun resolveBatteryDateText(
        context: Context,
        officialMethod: Method?,
        fallbackProperty: Int,
        sysfsPaths: List<String>
    ): String? {
        val official = officialMethod?.let { invokeStatic(it)?.toString() }
        formatBatteryDate(official)?.let { return it }

        val manager = runCatching { context.getSystemService(BatteryManager::class.java) }.getOrNull()
        val seconds = runCatching { manager?.getLongProperty(fallbackProperty) ?: 0L }.getOrDefault(0L)
        if (seconds > 0) formatBatteryDate(secondsToDate(seconds))?.let { return it }

        return sysfsPaths.asSequence()
            .mapNotNull(::readFirstLine)
            .mapNotNull(::formatBatteryDate)
            .firstOrNull()
    }

    private fun formatBatteryDate(candidate: String?): String? {
        val value = candidate?.trim() ?: return null
        if (value.length != 8 || !value.all(Char::isDigit) || value == "00000000" || value == "99999999") {
            return null
        }
        return runCatching {
            val parser = SimpleDateFormat(DATE_FORMAT, Locale.US).apply { isLenient = false }
            val date = parser.parse(value) ?: return@runCatching null
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMMM")
            SimpleDateFormat(pattern, Locale.getDefault()).format(date)
        }.getOrNull()
    }

    private fun secondsToDate(seconds: Long): String? = runCatching {
        SimpleDateFormat(DATE_FORMAT, Locale.US).format(Date(seconds * 1000L))
    }.getOrNull()

    private fun resolveCapacityText(method: Method?, sysfsPaths: List<String>): String {
        val official = method?.let { invokeStatic(it) }?.let { value ->
            when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
        }
        val raw = official ?: sysfsPaths.asSequence()
            .mapNotNull(::readFirstLine)
            .mapNotNull(String::toLongOrNull)
            .firstOrNull { it > 0 }
        if (raw == null || raw <= 0) return EMPTY_VALUE
        val milliampHours = if (raw > 100_000) raw / 1000 else raw
        return "$milliampHours mAh"
    }

    private fun invokeStatic(method: Method): Any? = runCatching {
        method.isAccessible = true
        method.invoke(null)
    }.getOrNull()

    private fun setPreferenceText(preference: Any?, text: String) {
        preference?.callMethodOrNull("setText", text)
    }

    private fun updateDatePreference(preference: Any?, text: String?) {
        if (preference == null) return
        preference.callMethodOrNull("setVisible", !text.isNullOrBlank())
        text?.let { setPreferenceText(preference, it) }
    }

    private fun updateValuePreference(preference: Any?, text: String) {
        if (preference == null) return
        preference.callMethodOrNull("setVisible", true)
        setPreferenceText(preference, text)
    }

    private fun resolveBatteryTemperatureText(context: Context): String? {
        val tenthsCelsius = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra("temperature", Int.MIN_VALUE)
                ?.takeUnless { it == Int.MIN_VALUE }
        }.getOrNull()
        if (tenthsCelsius != null && tenthsCelsius != 0) {
            return formatTemperature(tenthsCelsius / 10.0)
        }

        return TEMPERATURE_PATHS.asSequence()
            .mapNotNull(::readFirstLine)
            .mapNotNull(String::toDoubleOrNull)
            .map { raw -> if (kotlin.math.abs(raw) >= 10_000) raw / 1000.0 else raw / 10.0 }
            .firstOrNull()
            ?.let(::formatTemperature)
    }

    private fun formatTemperature(celsius: Double): String =
        String.format(Locale.getDefault(), "%.1f °C", celsius)

    private fun resolveCycleCountText(): String? {
        val cycle = CYCLE_COUNT_PATHS.asSequence()
            .mapNotNull(::readFirstLine)
            .mapNotNull(String::toIntOrNull)
            .firstOrNull { it >= 0 }
            ?: readMethods.fg1Cycle?.let(::invokeStaticInt)?.takeIf { it >= 0 }
            ?: readMethods.fg2Cycle?.let(::invokeStaticInt)?.takeIf { it >= 0 }
            ?: resolveMiChargeCycleCount()
            ?: return null
        return if (Locale.getDefault().language == "zh") "$cycle 次" else "$cycle cycles"
    }

    private fun resolveMiChargeCycleCount(): Int? = runCatching {
        val miChargeClass = "miui.util.IMiCharge".toClassOrNull() ?: return@runCatching null
        val instance = miChargeClass.getMethod("getInstance").invoke(null) ?: return@runCatching null
        miChargeClass.getMethod("getBatteryCycleCount").invoke(instance)?.toString()?.toIntOrNull()
    }.getOrNull()?.takeIf { it >= 0 }

    private fun invokeStaticInt(method: Method): Int? = runCatching {
        when (val value = invokeStatic(method)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }.getOrNull()

    private fun localizedTitle(chinese: String, english: String): String =
        if (Locale.getDefault().language == "zh") chinese else english

    private fun readFirstLine(path: String): String? = runCatching {
        val file = File(path)
        if (!file.exists()) null else file.bufferedReader().use { it.readLine() }
    }.getOrNull()

    private fun resolveReadMethods(apkPath: String?): BatteryReadMethods {
        val dexResolved = apkPath?.let { path ->
            DexKitManager.withBridge(path) { bridge ->
                BatteryReadMethods(
                    manufacturingDate = findDexMethod(bridge, "manufacturing_date", String::class.java),
                    firstUsageDate = findDexMethod(bridge, "first_usage_date", String::class.java),
                    fullCapacity = findDexMethod(bridge, "getBatteryChargeFull", Int::class.javaPrimitiveType!!),
                    designCapacity = findDexMethod(bridge, "charge_full_design", Int::class.javaPrimitiveType!!)
                )
            }
        }
        val direct = BatteryReadMethods(
            manufacturingDate = directMethod("nh.o", "e", String::class.java),
            firstUsageDate = directMethod("nh.o", "m", String::class.java),
            fullCapacity = directMethod("nh.i", "i", Int::class.javaPrimitiveType!!),
            designCapacity = directMethod("nh.i", "j", Int::class.javaPrimitiveType!!),
            fg1Cycle = directMethod("nh.o", "z", Int::class.javaPrimitiveType!!),
            fg2Cycle = directMethod("nh.o", "A", Int::class.javaPrimitiveType!!)
        )
        return BatteryReadMethods(
            manufacturingDate = dexResolved?.manufacturingDate ?: direct.manufacturingDate,
            firstUsageDate = dexResolved?.firstUsageDate ?: direct.firstUsageDate,
            fullCapacity = dexResolved?.fullCapacity ?: direct.fullCapacity,
            designCapacity = dexResolved?.designCapacity ?: direct.designCapacity,
            fg1Cycle = direct.fg1Cycle,
            fg2Cycle = direct.fg2Cycle
        )
    }

    private fun findDexMethod(
        bridge: org.luckypray.dexkit.DexKitBridge,
        string: String,
        returnType: Class<*>
    ): Method? {
        val candidates = bridge.findMethod {
            matcher {
                paramCount(0)
                addUsingString(string, StringMatchType.Equals)
            }
        }.toList()
        return candidates.mapNotNull(::materialize)
            .filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 0 &&
                    method.returnType == returnType
            }
            .singleOrNull()
    }

    private fun materialize(data: MethodData): Method? = runCatching {
        data.getMethodInstance(classLoader)
    }.onFailure {
        DebugLog.w(TAG, "failed to inspect ${data.className}#${data.methodName}", it)
    }.getOrNull()

    private fun directMethod(className: String, name: String, returnType: Class<*>): Method? =
        className.toClassOrNull()?.declaredMethods?.singleOrNull { method ->
            method.name == name &&
                Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                method.returnType == returnType
        }?.apply { isAccessible = true }

    private fun clearCapturedPreferences() {
        batteryInfoCategory = null
        currentTemperaturePreference = null
        cycleCountPreference = null
        productionDatePreference = null
        firstUseDatePreference = null
        designCapacityPreference = null
        fullCapacityPreference = null
        batteryContext = null
    }

    private data class BatteryReadMethods(
        val manufacturingDate: Method? = null,
        val firstUsageDate: Method? = null,
        val fullCapacity: Method? = null,
        val designCapacity: Method? = null,
        val fg1Cycle: Method? = null,
        val fg2Cycle: Method? = null
    )
}
