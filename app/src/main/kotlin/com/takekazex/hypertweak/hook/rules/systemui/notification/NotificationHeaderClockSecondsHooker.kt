package com.takekazex.hypertweak.hook.rules.systemui.notification

import com.takekazex.hypertweak.hook.base.HostRecoveryPhase
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import android.text.format.DateFormat
import android.graphics.Rect
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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

/**
 * Adds seconds to the status-bar clock or to the large clock at the top of the expanded notification
 * shade. The long-press mode is temporary: it lasts at most five minutes and does not modify the
 * persistent notification-shade setting.
 *
 * The stock OS4 clock updates from KeyguardUpdateMonitor's minute-level callback. The hook keeps
 * the normal MiuiClock rendering intact, then refreshes only the selected clock once per second
 * while its view is attached.
 */
object NotificationHeaderClockSecondsHooker : StaticHooker() {
    // Published on settings acceptance; hook callbacks perform no storage reads.
    private val settingNotificationHeaderClockSeconds by hookSetting(false) {
        Preferences.getBoolean(SystemUiSettingsKeys.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS, false)
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
    private val expiryCallbacks = generationMainResource(WeakHashMap<View, Runnable>()) { it.clear() }
    private val expiryTokens = generationMainResource(WeakHashMap<View, Long>()) { it.clear() }
    private val trackedClocks = generationMainResource(WeakHashMap<View, Boolean>()) { it.clear() }
    private val temporaryOverrides = generationMainResource(WeakHashMap<View, Boolean>()) { it.clear() }
    private val restoringStock = generationMainResource(WeakHashMap<View, Boolean>()) { it.clear() }
    private class PendingPress(val clock: WeakReference<TextView>, val gesture: ClockLongPressGesture) {
        lateinit var callback: Runnable
    }
    private val pendingPresses = generationMainResource(WeakHashMap<View, PendingPress>()) { it.clear() }
    private val callbacksLock = Any()

    private var persistentHeaderEnabled by generationState { false }

    private var clockClass: Class<*>? by generationState { null }

    private var headerClockClass: Class<*>? by generationState { null }

    private var updateTimeMethod: Method? by generationState { null }

    private var statusBarClockId by generationState { 0 }

    private var nextExpiryToken = 0L

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
     * Accepted-snapshot refresh. Enabling starts the header clock without a second restart;
     * disabling stops the tick and restores the stock clock on the existing header views.
     */
    fun refreshSettings() {
        val next = settingNotificationHeaderClockSeconds
        val changed = next != persistentHeaderEnabled
        persistentHeaderEnabled = next
        HostAccess.onMain {
            if (retiring) return@onMain
            val views = (tickCallbacks.keys + trackedClocks.keys + temporaryOverrides.keys)
                .filterIsInstance<TextView>().distinct()
            if (next) {
                views.filter { it.isAttachedToWindow }.forEach { view ->
                    targetOf(view)?.let { refresh(view, it, forceStock = true) }
                }
            } else if (changed) {
                views.forEach { view ->
                    cancelTick(view)
                    restoreStock(view)
                }
            }
        }
        DebugLog.i(TAG, "settings applied persistentHeader=" + persistentHeaderEnabled)
    }

    override fun onPrepareHotReload() {
        retiring = true
        HostAccess.onMain {
            synchronized(callbacksLock) {
                tickCallbacks.forEach { (view, callback) -> view.removeCallbacks(callback) }
                expiryCallbacks.forEach { (view, callback) -> view.removeCallbacks(callback) }
                pendingPresses.forEach { (window, press) -> window.removeCallbacks(press.callback) }
                pendingPresses.clear()
                val clocks = (tickCallbacks.keys + trackedClocks.keys).distinct()
                clocks.forEach { view ->
                    (view as? TextView)?.let(::restoreStock)
                }
                tickCallbacks.clear()
                expiryCallbacks.clear()
                expiryTokens.clear()
                trackedClocks.clear()
                temporaryOverrides.clear()
                restoringStock.clear()
            }
            persistentHeaderEnabled = false
            clockClass = null
            headerClockClass = null
            updateTimeMethod = null
            statusBarClockId = 0
        }
    }

    override fun onHook() {
        retiring = false
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

        resolvedClockClass.findMethodOrNull { name("onAttachedToWindow"); noParams() }?.hook {
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

        resolvedClockClass.findMethodOrNull { name("onDetachedFromWindow"); noParams() }?.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "onDetachedFromWindow", Unit) {
                    val view = param.thisObject as? View ?: return@open
                    if (targetOf(view) == null) return@open
                    cancelTick(view)
                    cancelExpiry(view)
                    synchronized(callbacksLock) {
                        temporaryOverrides.remove(view)
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
                view.resources.getIdentifier(
                    STATUS_BAR_CLOCK_ENTRY,
                    "id",
                    SYSTEM_UI_PACKAGE
                )
            }.getOrDefault(0)
            return statusBarClockId
        }
    }

    private fun baselineSecondsEnabled(target: ClockTarget): Boolean =
        target == ClockTarget.NOTIFICATION_HEADER && persistentHeaderEnabled

    private fun isSecondsEnabled(view: View, target: ClockTarget): Boolean =
        synchronized(callbacksLock) {
            temporaryOverrides[view] ?: baselineSecondsEnabled(target)
        }

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
        val textView = view as? TextView ?: return false
        if (retiring || !view.isAttachedToWindow) return false

        val currentlyEnabled = isSecondsEnabled(view, target)
        val baseline = baselineSecondsEnabled(target)
        val newOverride = !currentlyEnabled
        synchronized(callbacksLock) {
            if (newOverride == baseline) {
                temporaryOverrides.remove(view)
            } else {
                temporaryOverrides[view] = newOverride
            }
        }

        if (newOverride == baseline) {
            cancelExpiry(view)
        } else {
            scheduleExpiry(view)
        }
        refresh(textView, target, forceStock = true)
        DebugLog.d(
            TAG,
            "long press ${target.name.lowercase()}: seconds=$newOverride, temporary=" +
                (newOverride != baseline)
        )
        return true
    }

    private fun refresh(view: TextView, target: ClockTarget, forceStock: Boolean = false) {
        if (retiring || !view.isAttachedToWindow) return
        if (isSecondsEnabled(view, target)) {
            renderSeconds(view)
            schedule(view)
        } else {
            cancelTick(view)
            if (forceStock) restoreStock(view)
        }
    }

    private fun renderSeconds(view: TextView) {
        if (retiring || !view.isAttachedToWindow) return
        val context = view.context
        val locale = context.resources.configuration.locales[0]
        val skeleton = if (DateFormat.is24HourFormat(context)) "Hms" else "hmsa"
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
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
        if (target == null || !view.isAttachedToWindow || !isSecondsEnabled(view, target)) {
            cancelTick(view)
            return
        }

        val callback = synchronized(callbacksLock) {
            tickCallbacks[view] ?: Runnable {
                HookFailurePolicy.open(TAG, "secondTick", Unit) {
                    val currentTarget = targetOf(view)
                    if (retiring || !view.isAttachedToWindow || currentTarget == null ||
                        !isSecondsEnabled(view, currentTarget)
                    ) {
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

    private fun scheduleExpiry(view: View) {
        if (retiring || !view.isAttachedToWindow) return
        val token: Long
        val callback: Runnable
        synchronized(callbacksLock) {
            expiryCallbacks[view]?.let { runCatching { view.removeCallbacks(it) } }
            token = ++nextExpiryToken
            expiryTokens[view] = token
            callback = Runnable {
                val isCurrent = synchronized(callbacksLock) {
                    if (expiryTokens[view] != token) {
                        false
                    } else {
                        expiryTokens.remove(view)
                        expiryCallbacks.remove(view)
                        temporaryOverrides.remove(view)
                        true
                    }
                }
                if (!isCurrent) return@Runnable
                HookFailurePolicy.open(TAG, "temporaryExpiry", Unit) {
                    val target = targetOf(view) ?: return@open
                    refresh(view as TextView, target, forceStock = true)
                    DebugLog.d(TAG, "temporary clock seconds expired")
                }
            }
            expiryCallbacks[view] = callback
        }
        view.postDelayed(callback, TEMPORARY_SECONDS_TIMEOUT_MS)
    }

    private fun cancelExpiry(view: View) {
        val callback = synchronized(callbacksLock) {
            expiryTokens.remove(view)
            expiryCallbacks.remove(view)
        } ?: return
        runCatching { view.removeCallbacks(callback) }
    }

    private fun isRestoringStock(view: View): Boolean =
        synchronized(callbacksLock) { restoringStock.containsKey(view) }
}
