package com.takekazex.hypertweak.hook.rules.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import android.app.KeyguardManager
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.rules.systemui.icon.StatusIconHostAccess
import com.takekazex.hypertweak.util.DebugLog
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Appends live charging telemetry to the lockscreen's bottom charging indication, OS4 SystemUI.
 *
 * The "已充满电 / 充电中xx% / 极速充电xx%" line at the bottom of the lockscreen is produced by
 * `KeyguardIndicationController.updateDeviceEntryIndication(boolean)` and rendered through
 * `KeyguardIndicationRotateTextViewController.showIndication(int)` under the battery/charging
 * role `3` (the same role the lockscreen's reverse-charging hint uses; role 13 is the
 * dismissible swipe hint). When it renders, this hooker appends live values on a separate,
 * slightly smaller line below the charging text by default, or a safe-width single row:
 * - fields: any of wattage / voltage / current / temperature, bitmask
 *   `KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS`;
 * - refresh interval: `KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS`.
 * The main switch gates hook installation and still needs a SystemUI restart; the layout and telemetry
 * sub-options are re-read on every render (Preferences memo TTL is 100 ms), so they apply live.
 *
 * Data sources (all available to SystemUI, which runs with BATTERY_STATS):
 * - current: `BatteryManager.getIntProperty(CURRENT_NOW)` in µA, falling back to
 *   `CURRENT_AVERAGE`, then `/sys/class/power_supply/battery/current_now`. The sign is
 *   device-dependent, so only the magnitude is used.
 * - voltage (mV) and temperature (tenths °C): the sticky `ACTION_BATTERY_CHANGED` broadcast.
 * - real-time wattage = |current µA| × voltage mV / 1e9.
 *
 * The base comes from the controller's current native message, including animated handoffs.
 * BottomIndicationLayout owns reversible layout and overflow independently of telemetry.
 */
object LockscreenChargingDetailHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RECREATE

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
    private var messageField: Field? = null
    private var getIntProperty: Method? = null

    private val layouts = WeakHashMap<TextView, BottomIndicationLayout>()
    private var recentController: WeakReference<Any?> = WeakReference(null)

    @Volatile
    private var reportedFirstAppend = false

    private var plugged = false
    private var interactive = false
    private var keyguard = false
    private var receiverContext: Context? = null
    private var worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HT-ChargeSample").apply { isDaemon = true }
    }
    private var sampleGate = ChargingSampleGate()
    private var activeView = WeakReference<TextView>(null)
    private var observedView = WeakReference<TextView>(null)
    private var observer: ViewTreeObserver? = null
    private var samplingActive = false
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        boundary {
            if (displayDemanded() != samplingActive) reconcile()
        }
        true
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = boundary { observe(v as TextView); reconcile() }
        override fun onViewDetachedFromWindow(v: View) = boundary {
            stopObserving()
            stopRefresh()
            layouts[v]?.restore()
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = boundary {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    plugged = intent.getIntExtra("plugged", 0) != 0
                    cachedVoltageMv = intent.getIntExtra("voltage", -1)
                    cachedTempTenths = intent.getIntExtra("temperature", Int.MIN_VALUE)
                }
                Intent.ACTION_SCREEN_OFF -> interactive = false
                Intent.ACTION_SCREEN_ON -> {
                    interactive = true
                    keyguard = (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
                }
                Intent.ACTION_USER_PRESENT -> keyguard = false
            }
            reconcile()
        }
    }
    private var cachedCurrentUa = 0L
    private var cachedVoltageMv = -1
    private var cachedTempTenths = Int.MIN_VALUE

    // Bumped on every onHook/onPrepareHotReload so stale refresh loops die on hot reload.
    @Volatile
    private var refreshGeneration = 0

    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

    internal fun recoverController(controller: Any) {
        if (enabled) attachDetail(controller)
    }

    override fun saveHotReloadState(): Any? = StatusIconHostAccess.onMain { recentController.get() }
    override fun restoreHotReloadState(state: Any?) {
        if (!enabled || state == null) return
        StatusIconHostAccess.onMain { attachDetail(state) }
    }

    override fun onPrepareHotReload() {
        enabled = false
        refreshGeneration++
        StatusIconHostAccess.onMain {
            stopRefresh()
            stopObserving()
            observedView.get()?.removeOnAttachStateChangeListener(attachListener)
            observedView.clear()
            activeView.clear()
            receiverContext?.let { context ->
                boundary { context.unregisterReceiver(batteryReceiver) }
            }
            receiverContext = null
            worker.shutdownNow()
            currIndicationTypeField = null
            viewField = null
            messageField = null
            getIntProperty = null
            layouts.values.forEach { layout ->
                HookFailurePolicy.open(TAG, "restore layout", Unit) { layout.dispose() }
            }
            layouts.clear()
            recentController.clear()
        }
        reportedFirstAppend = false
        resetTelemetry()
    }

    override fun onHook() {
        enabled = Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL, false)
        refreshGeneration++
        if (worker.isShutdown) {
            sampleGate = ChargingSampleGate()
            worker = Executors.newSingleThreadExecutor { task ->
                Thread(task, "HT-ChargeSample").apply { isDaemon = true }
            }
        }
        if (!enabled) {
            DebugLog.hookSkippedDebug(TAG, "keyguard charging indication", "disabled")
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
        messageField = runCatching {
            clazz.getDeclaredField("mCurrMessage").apply { isAccessible = true }
        }.getOrElse {
            DebugLog.hookSkipped(TAG, "$ROTATE_VC#mCurrMessage", "field not found")
            return
        }

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

        DebugLog.d(TAG, "keyguard charging indication detail enabled")
    }

    // ─── Live sub-option readers (memo TTL 100 ms, so no SystemUI restart required) ───

    private fun fields(): Int =
        Preferences.getInt(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS, DEFAULT_FIELDS)

    private fun refreshIntervalMs(): Int =
        Preferences.getInt(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS, DEFAULT_INTERVAL_MS)
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    private fun boundary(action: () -> Unit) =
        HookFailurePolicy.open(TAG, "charging lifecycle", Unit, action)

    private fun attachDetail(controller: Any?) {
        if (!enabled || controller == null) return
        val view = runCatching { viewField?.get(controller) as? TextView }.getOrNull() ?: return
        if (recentController.get() !== controller || observedView.get() !== view) {
            stopRefresh()
            stopObserving()
            observedView.get()?.let {
                it.removeOnAttachStateChangeListener(attachListener)
                layouts[it]?.restore()
            }
            observedView = WeakReference(view)
            recentController = WeakReference(controller)
            view.addOnAttachStateChangeListener(attachListener)
            if (view.isAttachedToWindow) observe(view)
        }
        bindBatteryEvents(view.context)
        reconcile()
    }

    private fun bindBatteryEvents(context: Context) {
        if (receiverContext != null) return
        val app = context.applicationContext ?: context
        interactive = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
        keyguard = (app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val sticky = app.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        receiverContext = app
        sticky?.let { batteryReceiver.onReceive(app, it) }
    }

    private fun observe(view: TextView) {
        stopObserving()
        observer = view.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
    }

    private fun stopObserving() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        observer = null
    }

    private fun displayDemanded(): Boolean {
        val controller = recentController.get()
        val view = observedView.get()
        val batteryRole = controller != null && runCatching {
            currIndicationTypeField?.getInt(controller) == BATTERY_ROLE
        }.getOrDefault(false)
        return enabled && plugged && interactive && keyguard && batteryRole &&
            view != null && view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE
    }

    private fun reconcile() {
        val controller = recentController.get()
        val view = observedView.get()
        val visible = displayDemanded()
        if (!visible) {
            if (samplingActive) stopRefresh()
            view?.let { layouts[it]?.restore() }
            return
        }
        if (!samplingActive) {
            samplingActive = true
            activeView = WeakReference(view)
            sampleGate.start()
            scheduleRefresh()
        }
        if (view != null && controller != null) {
            renderDetail(controller, view)
            requestSample(view)
        }
    }

    private fun renderDetail(controller: Any, view: TextView) {
        val base = (messageField?.get(controller) as? CharSequence)?.toString()?.trim().orEmpty()
        val detail = buildDetail()
        if (base.isEmpty() || detail == null) {
            layouts[view]?.restore()
            return
        }
        layouts.getOrPut(view) { BottomIndicationLayout(view) }.show(base, detail,
            Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_CHARGING_DETAIL_TWO_ROWS, true))
        if (!reportedFirstAppend) {
            reportedFirstAppend = true
            DebugLog.d(TAG, "appended live charge detail: $detail")
        }
    }

    // Basic battery values are event-cached; only current/power require periodic IPC/sysfs.
    private fun requestSample(view: TextView) {
        if (!samplingActive || !needsCurrent(fields())) return
        val gate = sampleGate
        val ticket = gate.request(SystemClock.uptimeMillis(), refreshIntervalMs().toLong()) ?: return
        val gen = refreshGeneration
        val target = WeakReference(view)
        val context = view.context.applicationContext ?: view.context
        val method = getIntProperty
        worker.execute {
            val current = runCatching {
                readCurrentUa(context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager, method)
            }.onFailure { DebugLog.w(TAG, "charging sample failed", it) }.getOrDefault(0L)
            refreshHandler.post {
                val currentOwner = gate.complete(ticket)
                if (currentOwner && gen == refreshGeneration && samplingActive && target.get() === activeView.get()) {
                    boundary {
                        cachedCurrentUa = current
                        reconcile()
                    }
                } else if (enabled) boundary { reconcile() }
            }
        }
    }

    internal fun needsCurrent(flags: Int): Boolean = flags and (FIELD_WATTAGE or FIELD_CURRENT) != 0

    private fun readCurrentUa(batteryManager: BatteryManager?, method: Method?): Long {
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

    private fun stopRefresh() {
        refreshGeneration++
        samplingActive = false
        sampleGate.stop()
        activeView.clear()
        refreshRunnable?.let(refreshHandler::removeCallbacks)
        refreshRunnable = null
        cachedCurrentUa = 0L
    }

    private fun scheduleRefresh() {
        val gen = refreshGeneration
        refreshRunnable?.let(refreshHandler::removeCallbacks)
        val runnable = object : Runnable {
            override fun run() {
                if (gen != refreshGeneration || !samplingActive) return
                boundary { reconcile() }
                if (gen == refreshGeneration && samplingActive) {
                    refreshHandler.postDelayed(this, refreshIntervalMs().toLong())
                }
            }
        }
        refreshRunnable = runnable
        refreshHandler.postDelayed(runnable, refreshIntervalMs().toLong())
    }

    private fun abs(value: Long): Long = if (value < 0) -value else value

    private fun resetTelemetry() {
        cachedCurrentUa = 0L
        cachedVoltageMv = -1
        cachedTempTenths = Int.MIN_VALUE
    }
}
