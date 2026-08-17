package com.takekazex.hypertweak.hook.rules.systemui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale
import java.util.WeakHashMap

/**
 * Appends live charging telemetry to the lockscreen's bottom charging indication, OS4 SystemUI.
 *
 * The "已充满电 / 充电中xx% / 极速充电xx%" line at the bottom of the lockscreen is produced by
 * `KeyguardIndicationController.updateDeviceEntryIndication(boolean)` and rendered through
 * `KeyguardIndicationRotateTextViewController.showIndication(int)` under the battery/charging
 * role `3` (the same role the lockscreen's reverse-charging hint uses; role 13 is the
 * dismissible swipe hint). When it renders, this hooker appends live values. The layout is
 * configurable live:
 * - multi-line (default): the detail sits on its own, slightly smaller line below the charging
 *   text, so the single-line marquee never scrolls; `KEY_LOCKSCREEN_CHARGING_DETAIL_MULTILINE`;
 * - fields: any of wattage / voltage / current / temperature, bitmask
 *   `KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS`;
 * - refresh interval: `KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS`.
 * The main switch gates hook installation and still needs a SystemUI restart; the three
 * sub-options are re-read on every render (Preferences memo TTL is 100 ms), so they apply live.
 *
 * Data sources (all available to SystemUI, which runs with BATTERY_STATS):
 * - current: `BatteryManager.getIntProperty(CURRENT_NOW)` in µA, falling back to
 *   `CURRENT_AVERAGE`, then `/sys/class/power_supply/battery/current_now`. The sign is
 *   device-dependent, so only the magnitude is used.
 * - voltage (mV) and temperature (tenths °C): the sticky `ACTION_BATTERY_CHANGED` broadcast.
 * - real-time wattage = |current µA| × voltage mV / 1e9.
 *
 * The pristine base message is remembered per view (WeakHashMap) and only reused when it still
 * prefixes the current text, so a changing system message (e.g. 充电保护中) is never glued to a
 * stale base.
 */
object LockscreenChargingDetailHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "LockscreenChargeDetail"
    private const val ROTATE_VC = "com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController"

    /** The battery/charging role id used by `updateDeviceEntryIndication`. */
    private const val BATTERY_ROLE = 3

    const val FIELD_WATTAGE = 1
    const val FIELD_VOLTAGE = 2
    const val FIELD_CURRENT = 4
    const val FIELD_TEMPERATURE = 8
    const val DEFAULT_FIELDS = FIELD_WATTAGE or FIELD_VOLTAGE or FIELD_CURRENT or FIELD_TEMPERATURE
    const val DEFAULT_INTERVAL_MS = 2_000
    private const val MIN_INTERVAL_MS = 1_000
    private const val MAX_INTERVAL_MS = 10_000

    @Volatile
    private var enabled = false

    private var currIndicationTypeField: Field? = null
    private var viewField: Field? = null
    private var getIntProperty: Method? = null

    private val baseMessageByView = WeakHashMap<TextView, String>()
    private val multilineConfigured = WeakHashMap<TextView, Boolean>()
    private var recentController: WeakReference<Any?> = WeakReference(null)

    @Volatile
    private var reportedFirstAppend = false

    private var lastFetchUptime = 0L
    private var cachedCurrentUa = 0L
    private var cachedVoltageMv = -1
    private var cachedTempTenths = Int.MIN_VALUE

    // Bumped on every onHook/onPrepareHotReload so stale refresh loops die on hot reload.
    @Volatile
    private var refreshGeneration = 0

    override fun onPrepareHotReload() {
        enabled = false
        currIndicationTypeField = null
        viewField = null
        getIntProperty = null
        baseMessageByView.clear()
        multilineConfigured.clear()
        recentController.clear()
        refreshGeneration++
        reportedFirstAppend = false
        resetTelemetry()
    }

    override fun onHook() {
        enabled = Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL, false)
        refreshGeneration++
        if (!enabled) {
            DebugLog.hookSkipped(TAG, "keyguard charging indication", "disabled")
            return
        }

        val clazz = runCatching { classLoader.loadClass(ROTATE_VC) }.getOrElse {
            DebugLog.hookSkipped(TAG, ROTATE_VC, "class not found")
            return
        }

        val typeField = runCatching {
            clazz.getDeclaredField("mCurrIndicationType").apply { isAccessible = true }
        }.getOrElse {
            DebugLog.hookSkipped(TAG, "$ROTATE_VC#mCurrIndicationType", "field not found")
            return
        }
        currIndicationTypeField = typeField

        // mView is declared `public final View` on the ViewController base class.
        val field = runCatching {
            clazz.getField("mView").apply { isAccessible = true }
        }.getOrElse {
            runCatching { clazz.getDeclaredField("mView").apply { isAccessible = true } }.getOrNull()
        }
        viewField = field ?: run {
            DebugLog.hookSkipped(TAG, "$ROTATE_VC#mView", "field not found")
            return
        }

        // getIntProperty(int) is @hide (but public) — resolve it once for the hot path.
        getIntProperty = runCatching {
            BatteryManager::class.java.getMethod("getIntProperty", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()

        val showIndication = clazz.declaredMethods.firstOrNull {
            it.name == "showIndication" &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: run {
            DebugLog.hookSkipped(TAG, "$ROTATE_VC#showIndication(int)", "method not found")
            return
        }

        runCatching {
            showIndication.hook {
                after { param ->
                    HookFailurePolicy.open(TAG, "showIndication", Unit) {
                        attachDetail(param.thisObject)
                    }
                }
            }
        }.onFailure {
            DebugLog.hookFailed(TAG, "$ROTATE_VC#showIndication(int)", it)
        }

        scheduleRefresh()
        DebugLog.d(TAG, "keyguard charging indication detail enabled")
    }

    // ─── Live sub-option readers (memo TTL 100 ms, so no SystemUI restart required) ───

    private fun fields(): Int =
        Preferences.getInt(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS, DEFAULT_FIELDS)

    private fun refreshIntervalMs(): Int =
        Preferences.getInt(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS, DEFAULT_INTERVAL_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    private fun multiline(): Boolean =
        Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_MULTILINE, true)

    private fun attachDetail(controller: Any?) {
        if (!enabled || controller == null) return
        val typeField = currIndicationTypeField ?: return
        val field = viewField ?: return
        val type = runCatching { typeField.getInt(controller) }.getOrElse { return }
        if (type != BATTERY_ROLE) return
        recentController = WeakReference(controller)
        val view = runCatching { field.get(controller) as? TextView }.getOrNull() ?: return
        try {
            refreshTelemetry(view.context)
            appendDetail(view)
        } catch (t: Throwable) {
            DebugLog.w(TAG, "append detail failed", t)
        }
    }

    private fun appendDetail(view: TextView) {
        if (!isPluggedIn(view.context)) return
        val base = currentBaseMessage(view) ?: return
        val detail = buildDetail() ?: return
        val current = view.text?.toString().orEmpty()
        if (multiline()) {
            applyMultilineStyle(view)
            val combined = "$base\n$detail"
            if (current == combined) return
            val spannable = SpannableStringBuilder()
            spannable.append(base)
            spannable.append('\n')
            val detailStart = spannable.length
            spannable.append(detail)
            spannable.setSpan(
                RelativeSizeSpan(0.8f), detailStart, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            view.text = spannable
            baseMessageByView[view] = base
        } else {
            val combined = "$base · $detail"
            if (current == combined) return
            view.text = combined
            baseMessageByView[view] = base
        }
        if (!reportedFirstAppend) {
            reportedFirstAppend = true
            DebugLog.d(TAG, "appended live charge detail: $detail")
        }
    }

    /** Turns off single-line marquee so the two-line layout stays put instead of scrolling. */
    private fun applyMultilineStyle(view: TextView) {
        if (multilineConfigured[view] == true) return
        runCatching {
            view.isSingleLine = false
            view.maxLines = 2
            view.ellipsize = null
            view.gravity = Gravity.CENTER_HORIZONTAL
        }
        multilineConfigured[view] = true
    }

    /** The pristine base message for [view]: the stored one when it still prefixes the text. */
    private fun currentBaseMessage(view: TextView): String? {
        val current = view.text?.toString()?.trim().orEmpty()
        if (current.isEmpty()) return null
        val stored = baseMessageByView[view]
        return if (stored != null && current.startsWith(stored)) stored else current
    }

    // ─── Telemetry ─────────────────────────────────────────────────────────────

    private fun refreshTelemetry(context: Context) {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return
        cachedVoltageMv = sticky.getIntExtra("voltage", -1)
        cachedTempTenths = sticky.getIntExtra("temperature", Int.MIN_VALUE)

        val now = SystemClock.uptimeMillis()
        if (now - lastFetchUptime >= refreshIntervalMs() || cachedCurrentUa == 0L) {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            cachedCurrentUa = readCurrentUa(batteryManager)
            lastFetchUptime = now
        }
    }

    private fun isPluggedIn(context: Context): Boolean {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return false
        return sticky.getIntExtra("plugged", 0) != 0
    }

    private fun readCurrentUa(batteryManager: BatteryManager?): Long {
        val method = getIntProperty
        if (method != null && batteryManager != null) {
            var value = runCatching {
                method.invoke(batteryManager, 2) as? Int ?: Int.MIN_VALUE
            }.getOrDefault(Int.MIN_VALUE)
            if (value == Int.MIN_VALUE) {
                value = runCatching {
                    method.invoke(batteryManager, 3) as? Int ?: Int.MIN_VALUE
                }.getOrDefault(Int.MIN_VALUE)
            }
            if (value != Int.MIN_VALUE && value != 0) return abs(value.toLong())
        }
        // sysfs fallback (µA) for ROMs where BatteryService reports no current.
        val raw = runCatching {
            File("/sys/class/power_supply/battery/current_now").readText().trim().toLong()
        }.getOrNull() ?: return 0L
        return abs(raw)
    }

    private fun buildDetail(): String? {
        val flags = fields()
        val parts = mutableListOf<String>()
        val voltageMv = cachedVoltageMv
        val currentUa = cachedCurrentUa
        val tempTenths = cachedTempTenths
        if ((flags and FIELD_WATTAGE) != 0 && voltageMv > 0 && currentUa > 0) {
            val watt = currentUa.toDouble() * voltageMv / 1e9
            parts += if (watt >= 10.0) {
                String.format(Locale.US, "%.0fW", watt)
            } else {
                String.format(Locale.US, "%.1fW", watt)
            }
        }
        if ((flags and FIELD_VOLTAGE) != 0 && voltageMv > 0) {
            parts += String.format(Locale.US, "%.1fV", voltageMv / 1000.0)
        }
        if ((flags and FIELD_CURRENT) != 0 && currentUa > 0) {
            parts += String.format(Locale.US, "%.1fA", currentUa / 1_000_000.0)
        }
        if ((flags and FIELD_TEMPERATURE) != 0 && tempTenths != Int.MIN_VALUE && tempTenths > -500) {
            parts += "${tempTenths / 10}°C"
        }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    // ─── Live refresh ──────────────────────────────────────────────────────────

    private fun scheduleRefresh() {
        val gen = refreshGeneration
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (gen != refreshGeneration) return
                try {
                    recentController.get()?.let { attachDetail(it) }
                } catch (_: Throwable) {
                } finally {
                    if (gen == refreshGeneration) handler.postDelayed(this, refreshIntervalMs().toLong())
                }
            }
        }
        handler.postDelayed(runnable, refreshIntervalMs().toLong())
    }

    private fun abs(value: Long): Long = if (value < 0) -value else value

    private fun resetTelemetry() {
        lastFetchUptime = 0L
        cachedCurrentUa = 0L
        cachedVoltageMv = -1
        cachedTempTenths = Int.MIN_VALUE
    }
}
