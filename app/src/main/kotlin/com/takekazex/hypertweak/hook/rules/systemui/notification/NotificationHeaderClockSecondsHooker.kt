package com.takekazex.hypertweak.hook.rules.systemui.notification

import com.takekazex.hypertweak.hook.base.orSkip
import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import android.text.format.DateFormat
import android.graphics.Rect
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.PowerManager
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.TextView
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.hook.base.HostAccess
import com.takekazex.hypertweak.util.logging.DebugLog
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import com.takekazex.hypertweak.util.platform.ResourceLookup

/**
 * Adds seconds to the status-bar clock or to the large clock at the top of the expanded notification
 * shade. The long-press mode is temporary: it lasts at most five minutes and does not modify the
 * persistent clock settings.
 *
 * The stock OS4 clock updates from KeyguardUpdateMonitor's minute-level callback. The hook keeps
 * the normal MiuiClock rendering intact, then refreshes each enabled clock once per second
 * while its view is attached.
 */
object NotificationHeaderClockSecondsHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingNotificationHeaderClockSeconds by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS, false)
    }

    private val settingStatusBarClockSeconds by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_STATUS_BAR_CLOCK_SECONDS, false)
    }

    init {
        hostRecovery(HostRecoveryPhase.HEADER_DECORATIONS) {
            recoverExistingViews(it.views)
        }
    }

    private const val TAG = "NotificationClockSeconds"
    private const val CLOCK_CLASS = "com.android.systemui.statusbar.views.MiuiClock"
    private const val HEADER_CLOCK_CLASS =
        "com.android.systemui.statusbar.views.MiuiNotificationHeaderClock"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val STATUS_BAR_CLOCK_ENTRY = "clock"
    private const val TEMPORARY_SECONDS_TIMEOUT_MS = 5 * 60 * 1000L

    private enum class ClockTarget {
        STATUS_BAR,
        NOTIFICATION_HEADER
    }

    private val tickCallbacks = generationMainResource(WeakHashMap<View, Runnable>()) { it.clear() }
    private val expiryHandler = generationMainResource(Handler(Looper.getMainLooper())) {
        it.removeCallbacksAndMessages(null)
    }
    private var temporarySeconds by generationState { ClockSecondsPolicy(TEMPORARY_SECONDS_TIMEOUT_MS) }
    private val expiryCallback = Runnable {
        HookFailurePolicy.open(TAG, "temporaryExpiry", Unit) {
            if (retiring) return@open
            temporarySeconds.clear()
            refreshTrackedClocks()
            DebugLog.d(TAG, "temporary clock seconds expired")
        }
    }
    private val trackedClocks = generationMainResource(WeakHashMap<View, Boolean>()) { it.clear() }
    private val restoringStock = generationMainResource(WeakHashMap<View, Boolean>()) { it.clear() }
    private class PendingPress(val clock: WeakReference<TextView>, val gesture: ClockLongPressGesture) {
        lateinit var callback: Runnable
    }
    private val pendingPresses = generationMainResource(WeakHashMap<View, PendingPress>()) { it.clear() }
    private val callbacksLock = Any()
    private class DemandWatch(val observer: ViewTreeObserver, val listener: ViewTreeObserver.OnPreDrawListener)
    private val demandWatches = generationMainResource(WeakHashMap<View, DemandWatch>()) { it.clear() }
    private var demandContext: Context? by generationState { null }
    private var interactive by generationState { true }
    private var format24Hour: Boolean? by generationState { null }
    private var datePattern by generationState { ClockSecondsPattern() }
    private val formatObserver = object : ContentObserver(expiryHandler) {
        override fun onChange(selfChange: Boolean) {
            HookFailurePolicy.open(TAG, "clock format change", Unit) {
                if (!retiring) { invalidateFormat(); refreshTrackedClocks() }
            }
        }
    }
    private val demandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            HookFailurePolicy.open(TAG, "clock demand event", Unit) {
                if (retiring) return@open
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> interactive = false
                    Intent.ACTION_SCREEN_ON -> interactive = true
                    else -> invalidateFormat()
                }
                refreshTrackedClocks()
            }
        }
    }

    private fun invalidateFormat() { format24Hour = null; datePattern.clear() }

    private fun bindDemandEvents(view: View) {
        if (demandContext != null) return
        val context = view.context.applicationContext ?: return
        // A live clock exists after Application publication; retain this exact receiver key.
        runCatching { context.registerReceiver(demandReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED); addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction("android.intent.action.USER_SWITCHED")
        }, Context.RECEIVER_NOT_EXPORTED) }.onFailure {
            DebugLog.w(TAG, "clock demand receiver unavailable", it)
        }.getOrElse { return }
        demandContext = context
        interactive = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        HookFailurePolicy.open(TAG, "clock format observer", Unit) {
            context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.TIME_12_24),
                false, formatObserver)
        }
    }

    private fun demanded(view: View, target: ClockTarget): Boolean = ClockSecondsDemand.active(
        isSecondsEnabled(target), view.isAttachedToWindow, view.isShown,
        view.windowVisibility == View.VISIBLE, interactive, retiring)

    private fun watchDemand(view: TextView) {
        if (demandWatches.containsKey(view) || !view.isAttachedToWindow) return
        val weak = WeakReference(view)
        val listener = ViewTreeObserver.OnPreDrawListener {
            HookFailurePolicy.open(TAG, "clock visibility", Unit) {
                val clock = weak.get() ?: return@open
                val target = targetOf(clock) ?: return@open
                if (demanded(clock, target) != tickCallbacks.containsKey(clock)) refresh(clock, target)
            }
            true
        }
        view.viewTreeObserver.also { observer ->
            observer.addOnPreDrawListener(listener)
            demandWatches[view] = DemandWatch(observer, listener)
        }
    }

    private fun unwatchDemand(view: View) {
        demandWatches.remove(view)?.let { if (it.observer.isAlive) it.observer.removeOnPreDrawListener(it.listener) }
    }

    private var persistentHeaderEnabled by generationState { false }

    private var clockClass: Class<*>? by generationState { null }

    private var headerClockClass: Class<*>? by generationState { null }

    private var updateTimeMethod: Method? by generationState { null }

    private var statusBarClockId by generationState { 0 }

    private var persistentStatusBarEnabled by generationState { false }

    @Volatile
    private var retiring = false
    init { generationCleanup { retiring = true } }

    internal fun recoverExistingViews(views: List<View>) {
        if (retiring) return
        views.filterIsInstance<TextView>().forEach { view ->
            val target = targetOf(view) ?: return@forEach
            trackClock(view)
            refresh(view, target, forceStock = true)
        }
    }

    /**
     * Apply each clock's accepted baseline live, preserving any active shared temporary mode.
     */
    fun refreshSettings() {
        HostAccess.onMain {
            if (retiring) return@onMain
            persistentHeaderEnabled = settingNotificationHeaderClockSeconds
            persistentStatusBarEnabled = settingStatusBarClockSeconds
            refreshTrackedClocks()
            DebugLog.i(TAG, "settings applied persistentHeader=$persistentHeaderEnabled, " +
                "persistentStatusBar=$persistentStatusBarEnabled")
        }
    }

    private fun refreshTrackedClocks() {
        (tickCallbacks.keys + trackedClocks.keys).filterIsInstance<TextView>().distinct().forEach { view ->
            targetOf(view)?.let { refresh(view, it, forceStock = true) }
        }
    }

    override fun onPrepareHotReload() {
        retiring = true
        HostAccess.onMain {
            synchronized(callbacksLock) {
                demandWatches.keys.toList().forEach(::unwatchDemand)
                demandContext?.let { context ->
                    HookFailurePolicy.open(TAG, "clock receiver retirement", Unit) { context.unregisterReceiver(demandReceiver) }
                    HookFailurePolicy.open(TAG, "clock format retirement", Unit) { context.contentResolver.unregisterContentObserver(formatObserver) }
                }
                demandContext = null
                invalidateFormat()
                tickCallbacks.forEach { (view, callback) -> view.removeCallbacks(callback) }
                expiryHandler.removeCallbacksAndMessages(null)
                temporarySeconds.clear()
                pendingPresses.forEach { (window, press) -> window.removeCallbacks(press.callback) }
                pendingPresses.clear()
                val clocks = (tickCallbacks.keys + trackedClocks.keys).distinct()
                clocks.forEach { view ->
                    (view as? TextView)?.let(::restoreStock)
                }
                tickCallbacks.clear()
                trackedClocks.clear()
                restoringStock.clear()
            }
            persistentStatusBarEnabled = false
            persistentHeaderEnabled = false
            clockClass = null
            headerClockClass = null
            updateTimeMethod = null
            statusBarClockId = 0
        }
    }

    override fun onHook() {
        retiring = false
        persistentStatusBarEnabled = settingStatusBarClockSeconds
        persistentHeaderEnabled = settingNotificationHeaderClockSeconds

        val resolvedClockClass = CLOCK_CLASS.toClassOrNull() ?: run {
            DebugLog.hookSkipped(TAG, CLOCK_CLASS, "class not found")
            return
        }
        clockClass = resolvedClockClass
        headerClockClass = HEADER_CLOCK_CLASS.toClassOrNull().also {
            if (it == null) DebugLog.hookSkipped(TAG, HEADER_CLOCK_CLASS, "class not found")
        }

        val updateTime = resolvedClockClass.findMethodOrNull {
            name("updateTime")
            noParams()
        }
        updateTimeMethod = updateTime
        var hookCount = 0
        // Observe the complete window stream, including moves consumed by the shade parent.
        // Never claim DOWN, alter the event/result, or make the clock clickable/long-clickable.
        listOf(
            "com.android.systemui.statusbar.phone.PhoneStatusBarView",
            "com.android.systemui.shade.NotificationShadeWindowView"
        ).forEach { name ->
            name.toClassOrNull()?.findMethodOrNull {
                name("dispatchTouchEvent")
                parameterTypes(MotionEvent::class.java)
            }?.hook {
                before { param ->
                    HookFailurePolicy.open(TAG, "observeClockTouch", Unit) {
                        val window = param.thisObject as? View ?: return@open
                        val event = param.args.firstOrNull() as? MotionEvent ?: return@open
                        observeTouch(window, event)
                    }
                }
            } ?: DebugLog.hookSkipped(TAG, "$name#dispatchTouchEvent(MotionEvent)", "method not found")
        }

        updateTime?.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "updateTime", Unit) {
                    val view = param.thisObject as? TextView ?: return@open
                    if (isRestoringStock(view)) return@open
                    val target = targetOf(view) ?: return@open
                    trackClock(view)
                    refresh(view, target)
                }
            }
        }?.also { hookCount++ } ?: DebugLog.hookSkipped(
            TAG,
            "$CLOCK_CLASS#updateTime()",
            "method not found"
        )

        resolvedClockClass.findMethodOrNull { name("onAttachedToWindow"); noParams() }.orSkip("NotificationHeaderClockSecondsHooker", "resolvedClockClass#onAttachedToWindow")?.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "onAttachedToWindow", Unit) {
                    val view = param.thisObject as? TextView ?: return@open
                    val target = targetOf(view) ?: return@open
                    trackClock(view)
                    refresh(view, target)
                }
            }
        }?.also { hookCount++ } ?: DebugLog.hookSkipped(
            TAG,
            "$CLOCK_CLASS#onAttachedToWindow()",
            "method not found"
        )

        resolvedClockClass.findMethodOrNull { name("onDetachedFromWindow"); noParams() }.orSkip("NotificationHeaderClockSecondsHooker", "resolvedClockClass#onDetachedFromWindow")?.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "onDetachedFromWindow", Unit) {
                    val view = param.thisObject as? View ?: return@open
                    if (targetOf(view) == null) return@open
                    cancelTick(view)
                    unwatchDemand(view)
                    synchronized(callbacksLock) {
                        trackedClocks.remove(view)
                    }
                    pendingPresses.keys.toList().forEach { window ->
                        if (pendingPresses[window]?.clock?.get() === view) cancelPress(window)
                    }
                }
            }
        }?.also { hookCount++ } ?: DebugLog.hookSkipped(
            TAG,
            "$CLOCK_CLASS#onDetachedFromWindow()",
            "method not found"
        )

        if (hookCount == 0) {
            persistentStatusBarEnabled = false
            persistentHeaderEnabled = false
            clockClass = null
            headerClockClass = null
            updateTimeMethod = null
            DebugLog.hookSkipped(TAG, "clock seconds hooks", "no hook methods found")
        } else {
            DebugLog.d(
                TAG,
                "clock seconds hooks enabled; persistentHeader=$persistentHeaderEnabled, " +
                    "long press timeout=${TEMPORARY_SECONDS_TIMEOUT_MS / 60_000}min"
            )
        }
    }

    private fun targetOf(view: View): ClockTarget? {
        val headerType = headerClockClass
        if (headerType?.isInstance(view) == true) return ClockTarget.NOTIFICATION_HEADER

        val baseType = clockClass ?: return null
        if (view.javaClass != baseType) return null
        val id = resolveStatusBarClockId(view)
        return if (id != 0 && view.id == id) ClockTarget.STATUS_BAR else null
    }

    @Suppress("DiscouragedApi")
    private fun resolveStatusBarClockId(view: View): Int {
        statusBarClockId.takeIf { it != 0 }?.let { return it }
        synchronized(callbacksLock) {
            statusBarClockId.takeIf { it != 0 }?.let { return it }
            statusBarClockId = runCatching {
                ResourceLookup.identifier(view.resources, 
                    STATUS_BAR_CLOCK_ENTRY,
                    "id",
                    SYSTEM_UI_PACKAGE
                )
            }.getOrDefault(0)
            return statusBarClockId
        }
    }

    private fun baselineSecondsEnabled(target: ClockTarget): Boolean = when (target) {
        ClockTarget.STATUS_BAR -> persistentStatusBarEnabled
        ClockTarget.NOTIFICATION_HEADER -> persistentHeaderEnabled
    }

    private fun isSecondsEnabled(target: ClockTarget): Boolean =
        temporarySeconds.enabled(baselineSecondsEnabled(target), SystemClock.uptimeMillis())

    private fun trackClock(view: TextView) {
        if (retiring || trackedClocks.containsKey(view)) return
        // The previous generation's setOnLongClickListener(null) leaves LONG_CLICKABLE set.
        // These stock clock classes install no long-click handler; clear that upgrade residue.
        if (view.isLongClickable) {
            view.setOnLongClickListener(null)
            view.isLongClickable = false
        }
        trackedClocks[view] = true
    }

    private fun cancelPress(window: View) {
        pendingPresses.remove(window)?.let { window.removeCallbacks(it.callback) }
    }

    private fun observeTouch(window: View, event: MotionEvent) {
        if (retiring) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPress(window)
                val bounds = Rect()
                val rootLocation = IntArray(2)
                window.rootView.getLocationOnScreen(rootLocation)
                val clock = trackedClocks.keys.filterIsInstance<TextView>().firstOrNull { view ->
                    view.rootView === window.rootView && view.isShown && view.alpha > 0f &&
                        view.getGlobalVisibleRect(bounds) && bounds.apply {
                            offset(rootLocation[0], rootLocation[1])
                        }.contains(event.rawX.toInt(), event.rawY.toInt())
                } ?: return
                val press = PendingPress(WeakReference(clock), ClockLongPressGesture(
                    event.x, event.y, ViewConfiguration.get(window.context).scaledTouchSlop.toFloat()
                ))
                val windowRef = WeakReference(window)
                press.callback = Runnable {
                    HookFailurePolicy.open(TAG, "clockLongPressTimeout", Unit) {
                        val owner = windowRef.get() ?: return@open
                        if (pendingPresses[owner] !== press) return@open
                        pendingPresses.remove(owner)
                        val target = press.clock.get() ?: return@open
                        if (!retiring && owner.isAttachedToWindow && target.isShown &&
                            target.alpha > 0f && target.rootView === owner.rootView && press.gesture.fire()
                        ) {
                            if (handleLongPress(target)) finishRecognizedLongPress(target)
                        }
                    }
                }
                pendingPresses[window] = press
                if (!window.postDelayed(press.callback, ViewConfiguration.getLongPressTimeout().toLong())) {
                    cancelPress(window)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val press = pendingPresses[window] ?: return
                if (event.pointerCount != 1) {
                    cancelPress(window)
                    return
                }
                // Batched motion may leave the slop and return within the same dispatch.
                for (index in 0 until event.historySize) {
                    press.gesture.move(event.getHistoricalX(index), event.getHistoricalY(index))
                }
                press.gesture.move(event.x, event.y)
                if (!press.gesture.pending) cancelPress(window)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> cancelPress(window)
        }
    }

    private fun finishRecognizedLongPress(view: View) {
        // The header clock also has a native click listener opening Clock. Cancel only the
        // child's pressed/tap state after recognition, leaving the window's drag stream intact.
        // View's native LONG_CLICKABLE path normally does both this and haptics for us.
        HookFailurePolicy.open(TAG, "cancelClockTap", Unit) {
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try {
                view.dispatchTouchEvent(cancel)
            } finally {
                cancel.recycle()
            }
        }
        HookFailurePolicy.open(TAG, "clockLongPressHaptic", Unit) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Unit
        }
    }

    private fun handleLongPress(view: View): Boolean {
        val target = targetOf(view) ?: return false
        if (retiring || !view.isAttachedToWindow) return false
        temporarySeconds.toggle(baselineSecondsEnabled(target), SystemClock.uptimeMillis())
        expiryHandler.removeCallbacks(expiryCallback)
        if (temporarySeconds.isActive(SystemClock.uptimeMillis())) {
            expiryHandler.postDelayed(expiryCallback, TEMPORARY_SECONDS_TIMEOUT_MS)
        }
        refreshTrackedClocks()
        DebugLog.d(TAG, "long press ${target.name.lowercase()}: shared temporary seconds=" +
            temporarySeconds.isActive(SystemClock.uptimeMillis()))
        return true
    }

    private fun refresh(view: TextView, target: ClockTarget, forceStock: Boolean = false) {
        if (retiring || !view.isAttachedToWindow) return
        if (isSecondsEnabled(target)) {
            bindDemandEvents(view)
            watchDemand(view)
            if (!demanded(view, target)) { cancelTick(view); return }
            renderSeconds(view)
            schedule(view)
        } else {
            unwatchDemand(view)
            cancelTick(view)
            if (forceStock) restoreStock(view)
        }
    }

    private fun renderSeconds(view: TextView) {
        if (retiring || !view.isAttachedToWindow) return
        val context = view.context
        val locale = context.resources.configuration.locales[0]
        val hour24 = format24Hour ?: DateFormat.is24HourFormat(context).also { format24Hour = it }
        val pattern = datePattern.get(locale, hour24, DateFormat::getBestDateTimePattern)
        val formatted = DateFormat.format(pattern, System.currentTimeMillis()).toString()
        // MiuiClock adds a trailing space when it is nested in MiuiNotificationHeaderView. Keep
        // that layout-specific padding behavior after replacing the text with the seconds form.
        val current = view.text?.toString().orEmpty()
        view.text = if (current.endsWith(" ")) "$formatted " else formatted
    }

    private fun restoreStock(view: TextView) {
        val method = updateTimeMethod ?: return
        val shouldInvoke = synchronized(callbacksLock) {
            if (restoringStock.containsKey(view)) {
                false
            } else {
                restoringStock[view] = true
                true
            }
        }
        if (!shouldInvoke) return

        try {
            HookFailurePolicy.open(TAG, "restoreStock", Unit) {
                method.invoke(view)
            }
        } finally {
            synchronized(callbacksLock) { restoringStock.remove(view) }
        }
    }

    private fun schedule(view: View) {
        if (retiring) return
        val target = targetOf(view)
        if (target == null || !demanded(view, target)) {
            cancelTick(view)
            return
        }

        val callback = synchronized(callbacksLock) {
            tickCallbacks[view] ?: Runnable {
                HookFailurePolicy.open(TAG, "secondTick", Unit) {
                    val currentTarget = targetOf(view)
                    if (currentTarget == null || !demanded(view, currentTarget)) {
                        cancelTick(view)
                        return@open
                    }
                    renderSeconds(view as TextView)
                    schedule(view)
                }
            }.also { tickCallbacks[view] = it }
        }
        view.removeCallbacks(callback)
        val now = System.currentTimeMillis()
        val delay = (1000L - now % 1000L).coerceAtLeast(50L)
        view.postDelayed(callback, delay)
    }

    private fun cancelTick(view: View) {
        val callback = synchronized(callbacksLock) { tickCallbacks.remove(view) } ?: return
        runCatching { view.removeCallbacks(callback) }
    }

    private fun isRestoringStock(view: View): Boolean =
        synchronized(callbacksLock) { restoringStock.containsKey(view) }
}
