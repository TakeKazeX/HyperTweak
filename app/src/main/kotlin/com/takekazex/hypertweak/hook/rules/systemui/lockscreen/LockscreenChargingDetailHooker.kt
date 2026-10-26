package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import com.takekazex.hypertweak.settings.contract.LockscreenChargingOptions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.PowerManager
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
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
 * The controller hook is installed at SystemUI startup regardless of the master switch; the
 * switch itself is read live on every render, so enabling it applies without a restart. The
 * layout and telemetry sub-options are also re-read on every render (Preferences memo TTL is
 * 100 ms). A SystemUI restart is only needed after updating the module itself.
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
    init {
        hostRecovery(HostRecoveryPhase.WINDOWS) {
            it.component?.let { component -> HostAccess.read(component, "keyguardIndicationControllerProvider") }
                ?.let { provider -> HostAccess.method(provider, "get")?.invoke(provider) }?.let { indication ->
                    val refresh = HostAccess.method(indication, "updateDeviceEntryIndication", Boolean::class.javaPrimitiveType!!)
                        ?: error("Missing native keyguard indication refresh")
                    refresh.invoke(indication, false)
                    HostAccess.read(indication, "mRotateTextViewController")?.let(::recoverController)
                }
        }
    }

    override val hotReloadMode = HotReloadMode.RECREATE

    private const val TAG = "LockscreenChargeDetail"
    private const val ROTATE_VC = "com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController"

    /** The battery/charging role id used by `updateDeviceEntryIndication`. */
    private const val BATTERY_ROLE = 3

    private fun detailEnabled(): Boolean =
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL, false)

    private var currIndicationTypeField: Field? = null
    private var viewField: Field? = null
    private var messageField: Field? = null
    private var rotateControllerField: Field? = null
    private var hostDozingField: Field? = null
    private var hostVisibleField: Field? = null
    private var rotateDozingField: Field? = null
    private var stateListenerField: Field? = null
    private var onDozingChanged: Method? = null
    private var indicationHost = WeakReference<Any>(null)
    private var hostSyncPending = false
    private val hostSync = Runnable {
        hostSyncPending = false
        boundary {
            val host = indicationHost.get() ?: return@boundary
            val controller = rotateControllerField?.get(host) ?: return@boundary
            attachDetail(controller)
            val view = observedView.get() ?: return@boundary
            // Theme/scene startup can leave the rotating controller in doze after its host
            // has resumed. Replay the native wake callback only after both have settled.
            if (detailEnabled() && interactive && hostVisibleField?.getBoolean(host) == true &&
                hostDozingField?.getBoolean(host) == false &&
                rotateDozingField?.getBoolean(controller) == true && view.isAttachedToWindow && view.isShown) {
                val listener = stateListenerField?.get(controller) ?: return@boundary
                DebugLog.i(TAG, "resync native indication wake: hostDozing=false rotateDozing=true")
                onDozingChanged?.invoke(listener, false)
                reconcile()
            }
        }
    }
    private var getIntProperty: Method? = null

    private val layouts = WeakHashMap<TextView, BottomIndicationLayout>()
    private var recentController: WeakReference<Any?> = WeakReference(null)

    private var reportedFirstAppend by generationState { false }

    private var interactive = false
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
        override fun onViewAttachedToWindow(v: View) = boundary {
            // observedView is a WeakReference<TextView>, so identity already implies the type.
            if (observedView.get() === v) {
                observe(v)
                reconcile()
                scheduleHostSync()
            }
        }
        override fun onViewDetachedFromWindow(v: View) = boundary {
            if (observedView.get() !== v) return@boundary
            stopObserving()
            stopRefresh()
            layouts[v]?.restore()
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = boundary {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> applyBatteryIntent(intent)
                Intent.ACTION_SCREEN_OFF -> interactive = false
                Intent.ACTION_SCREEN_ON -> interactive = true
            }
            reconcile()
            scheduleHostSync()
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
        if (detailEnabled()) attachDetail(controller)
    }

    override fun saveHotReloadState(): Any? = HostAccess.onMain {
        arrayOf(indicationHost.get(), recentController.get())
    }
    override fun restoreHotReloadState(state: Any?) {
        if (state !is Array<*> || state.size != 2) return
        HostAccess.onMain {
            indicationHost = WeakReference(state[0])
            state[1]?.let(::attachDetail)
            scheduleHostSync()
        }
    }

    override fun onPrepareHotReload() {
        refreshGeneration++
        HostAccess.onMain {
            stopRefresh()
            refreshHandler.removeCallbacks(hostSync)
            hostSyncPending = false
            indicationHost.clear()
            rotateControllerField = null
            hostDozingField = null
            hostVisibleField = null
            rotateDozingField = null
            stateListenerField = null
            onDozingChanged = null
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

        resetTelemetry()
    }

    override fun onHook() {
        refreshGeneration++
        if (worker.isShutdown) {
            sampleGate = ChargingSampleGate()
            worker = Executors.newSingleThreadExecutor { task ->
                Thread(task, "HT-ChargeSample").apply { isDaemon = true }
            }
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

        hookIndicationHost(clazz)
        DebugLog.i(TAG, "keyguard charging indication hooks installed; master=${detailEnabled()}")
    }

    private fun hookIndicationHost(rotateClass: Class<*>) = boundary {
        val hostClass = classLoader.loadClass("com.android.systemui.statusbar.KeyguardIndicationController")
        fun Class<*>.field(name: String) = getDeclaredField(name).apply { isAccessible = true }
        val owner = hostClass.field("mRotateTextViewController")
        val dozing = hostClass.field("mDozing")
        val visible = hostClass.field("mVisible")
        val rotateDozing = rotateClass.field("mIsDozing")
        val listener = rotateClass.field("mStatusBarStateListener")
        val wake = listener.type.getDeclaredMethod("onDozingChanged", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val targets = listOf(
            hostClass.getDeclaredMethod("setIndicationArea", android.view.ViewGroup::class.java),
            hostClass.getDeclaredMethod("updateDeviceEntryIndication", Boolean::class.javaPrimitiveType)
        )
        rotateControllerField = owner
        hostDozingField = dozing
        hostVisibleField = visible
        rotateDozingField = rotateDozing
        stateListenerField = listener
        onDozingChanged = wake
        targets.forEach { method ->
            method.hook {
                after { param -> boundary {
                    indicationHost = WeakReference(param.thisObject)
                    owner.get(param.thisObject)?.let(::attachDetail)
                    scheduleHostSync()
                } }
            }
        }
    }

    /**
     * Accepted-snapshot refresh for the charging detail. The options are read per pass, so this
     * only asks the living lock-screen indication to reconcile now instead of waiting for the
     * next battery broadcast or sampling tick; the master switch restores the native text.
     */
    fun refreshSettings() {
        refreshGeneration++
        if (detailEnabled()) {
            reconcile()
            scheduleHostSync()
        } else {
            stopRefresh()
            scheduleHostSync()
        }
        DebugLog.i(TAG, "settings applied enabled=" + detailEnabled() + " fields=" + fields())
    }

    private fun scheduleHostSync() {
        if (hostSyncPending || indicationHost.get() == null) return
        hostSyncPending = true
        refreshHandler.post(hostSync)
    }

    // ─── Live sub-option readers (memo TTL 100 ms, so no SystemUI restart required) ───

    private fun fields(): Int =
        Preferences.getInt(SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL_FIELDS, LockscreenChargingOptions.DEFAULT_FIELDS)

    private fun refreshIntervalMs(): Int =
        Preferences.getInt(SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL_INTERVAL_MS, LockscreenChargingOptions.DEFAULT_INTERVAL_MS)
            .coerceIn(LockscreenChargingOptions.MIN_INTERVAL_MS, LockscreenChargingOptions.MAX_INTERVAL_MS)

    private fun boundary(action: () -> Unit) =
        HookFailurePolicy.open(TAG, "charging lifecycle", Unit, action)

    private fun attachDetail(controller: Any?) {
        if (controller == null) return
        // A retired rotate controller can still receive a queued status/doze callback.
        // It must never take ownership away from the controller held by the current host.
        indicationHost.get()?.let { host ->
            if (rotateControllerField?.get(host) !== controller) return
        }
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
            DebugLog.i(TAG, "bound indication controller=${System.identityHashCode(controller)} attached=${view.isAttachedToWindow}")
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
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            app.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }.onSuccess { sticky ->
            receiverContext = app
            sticky?.let(::applyBatteryIntent)
        }.onFailure {
            DebugLog.w(TAG, "battery receiver registration failed", it)
        }
        if (cachedVoltageMv <= 0 || cachedTempTenths == Int.MIN_VALUE) {
            refreshBatterySnapshot(app)
        }
    }

    private fun applyBatteryIntent(intent: Intent) {
        cachedVoltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        cachedTempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    }

    private fun refreshBatterySnapshot(context: Context) {
        runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.onSuccess { intent ->
            intent?.let(::applyBatteryIntent)
        }.onFailure {
            DebugLog.w(TAG, "battery snapshot read failed", it)
        }
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
        return detailEnabled() && interactive && batteryRole &&
            view != null && view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE
    }

    private fun reconcile() {
        val controller = recentController.get()
        val view = observedView.get()
        val visible = displayDemanded()
        if (visible != samplingActive) {
            val host = indicationHost.get()
            DebugLog.i(TAG, "charging demand=$visible role=${controller?.let { currIndicationTypeField?.getInt(it) }} " +
                "interactive=$interactive shown=${view?.isShown} " +
                "hostDozing=${host?.let { hostDozingField?.getBoolean(it) }} " +
                "rotateDozing=${controller?.let { rotateDozingField?.getBoolean(it) }}")
        }
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
            Preferences.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_CHARGING_DETAIL_TWO_ROWS, true))
        if (!reportedFirstAppend) {
            reportedFirstAppend = true
            DebugLog.i(TAG, "appended live charge detail: $detail")
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
                } else if (detailEnabled()) boundary { reconcile() }
            }
        }
    }

    internal fun needsCurrent(flags: Int): Boolean = flags and (LockscreenChargingOptions.FIELD_WATTAGE or LockscreenChargingOptions.FIELD_CURRENT) != 0

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
        if ((flags and LockscreenChargingOptions.FIELD_WATTAGE) != 0 && voltageMv > 0 && currentUa > 0) {
            val watt = currentUa.toDouble() * voltageMv / 1e9
            parts += if (watt >= 10.0) {
                String.format(Locale.US, "%.0fW", watt)
            } else {
                String.format(Locale.US, "%.1fW", watt)
            }
        }
        if ((flags and LockscreenChargingOptions.FIELD_VOLTAGE) != 0 && voltageMv > 0) {
            parts += String.format(Locale.US, "%.1fV", voltageMv / 1000.0)
        }
        if ((flags and LockscreenChargingOptions.FIELD_CURRENT) != 0 && currentUa > 0) {
            parts += String.format(Locale.US, "%.1fA", currentUa / 1_000_000.0)
        }
        if ((flags and LockscreenChargingOptions.FIELD_TEMPERATURE) != 0 && tempTenths != Int.MIN_VALUE && tempTenths > -500) {
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
