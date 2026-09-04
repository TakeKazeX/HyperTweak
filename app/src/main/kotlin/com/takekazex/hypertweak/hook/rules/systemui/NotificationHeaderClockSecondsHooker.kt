package com.takekazex.hypertweak.hook.rules.systemui

import android.text.format.DateFormat
import android.view.View
import android.widget.TextView
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
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
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

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

    private val tickCallbacks = WeakHashMap<View, Runnable>()
    private val expiryCallbacks = WeakHashMap<View, Runnable>()
    private val expiryTokens = WeakHashMap<View, Long>()
    private val longPressInstalled = WeakHashMap<View, Boolean>()
    private val temporaryOverrides = WeakHashMap<View, Boolean>()
    private val restoringStock = WeakHashMap<View, Boolean>()
    private val callbacksLock = Any()

    @Volatile
    private var persistentHeaderEnabled = false

    @Volatile
    private var clockClass: Class<*>? = null

    @Volatile
    private var headerClockClass: Class<*>? = null

    @Volatile
    private var updateTimeMethod: Method? = null

    @Volatile
    private var statusBarClockId = 0

    private var nextExpiryToken = 0L

    override fun onPrepareHotReload() {
        persistentHeaderEnabled = false
        clockClass = null
        headerClockClass = null
        updateTimeMethod = null
        statusBarClockId = 0
        synchronized(callbacksLock) {
            tickCallbacks.forEach { (view, callback) ->
                runCatching { view.removeCallbacks(callback) }
            }
            expiryCallbacks.forEach { (view, callback) ->
                runCatching { view.removeCallbacks(callback) }
            }
            longPressInstalled.keys.forEach { view ->
                runCatching { view.setOnLongClickListener(null) }
            }
            tickCallbacks.clear()
            expiryCallbacks.clear()
            expiryTokens.clear()
            longPressInstalled.clear()
            temporaryOverrides.clear()
            restoringStock.clear()
        }
    }

    override fun onHook() {
        persistentHeaderEnabled = Preferences.getBoolean(
            Preferences.KEY_NOTIFICATION_HEADER_CLOCK_SECONDS,
            false
        )

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

        updateTime?.hook {
            after { param ->
                HookFailurePolicy.open(TAG, "updateTime", Unit) {
                    val view = param.thisObject as? TextView ?: return@open
                    if (isRestoringStock(view)) return@open
                    val target = targetOf(view) ?: return@open
                    installLongPressListener(view)
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
                    installLongPressListener(view)
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
                        longPressInstalled.remove(view)
                    }
                    runCatching { view.setOnLongClickListener(null) }
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

    private fun installLongPressListener(view: TextView) {
        val shouldInstall = synchronized(callbacksLock) {
            if (longPressInstalled.containsKey(view)) {
                false
            } else {
                longPressInstalled[view] = true
                true
            }
        }
        if (!shouldInstall) return

        runCatching {
            view.setOnLongClickListener { clicked ->
                HookFailurePolicy.open(TAG, "longPress", false) {
                    handleLongPress(clicked)
                }
            }
        }.onFailure {
            synchronized(callbacksLock) { longPressInstalled.remove(view) }
            DebugLog.w(TAG, "failed to install clock long-press listener", it)
        }
    }

    private fun handleLongPress(view: View): Boolean {
        val target = targetOf(view) ?: return false
        val textView = view as? TextView ?: return false
        if (!view.isAttachedToWindow) return false

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
        if (!view.isAttachedToWindow) return
        if (isSecondsEnabled(view, target)) {
            renderSeconds(view)
            schedule(view)
        } else {
            cancelTick(view)
            if (forceStock) restoreStock(view)
        }
    }

    private fun renderSeconds(view: TextView) {
        if (!view.isAttachedToWindow) return
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
        val target = targetOf(view)
        if (target == null || !view.isAttachedToWindow || !isSecondsEnabled(view, target)) {
            cancelTick(view)
            return
        }

        val callback = synchronized(callbacksLock) {
            tickCallbacks[view] ?: Runnable {
                HookFailurePolicy.open(TAG, "secondTick", Unit) {
                    val currentTarget = targetOf(view)
                    if (!view.isAttachedToWindow || currentTarget == null ||
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
        if (!view.isAttachedToWindow) return
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
