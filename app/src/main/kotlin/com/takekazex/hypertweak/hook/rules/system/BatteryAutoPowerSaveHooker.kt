package com.takekazex.hypertweak.hook.rules.system

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.base.HookFailurePolicy
import com.takekazex.hypertweak.hook.base.HotReloadMode
import com.takekazex.hypertweak.hook.base.StaticHooker
import com.takekazex.hypertweak.util.DebugLog
import io.github.lingqiqi5211.ezhooktool.xposed.EzXposed

/** Owns the battery monitor in SystemUI once its application context is available. */
object BatteryAutoPowerSaveHooker : StaticHooker() {
    override val hotReloadMode = HotReloadMode.RESTART_RECOMMENDED

    private const val TAG = "BatteryAutoPowerSave"
    private const val PACKAGE = "com.android.systemui"

    // Holds the application context only, which lives as long as the injected SystemUI process, so
    // the static link is not a leak. Deliberately not a weak reference: a collected monitor would
    // silently stop watching the battery.
    @SuppressLint("StaticFieldLeak")
    private var monitor: BatteryMonitor? = null

    override fun onHook() {
        if (hookParam.packageName != PACKAGE) return
        // Preference-channel recovery retries onHook after package-ready may already have run.
        val context = hookParam.appContext ?: runCatching { EzXposed.appContextOrNull }.getOrNull()
        if (context != null) onPackageReady(context)
        else DebugLog.d(TAG, "battery monitor waiting for the SystemUI context")
    }

    @Synchronized
    fun onPackageReady(context: Context) {
        if (hookParam.packageName != PACKAGE || monitor != null) return
        val appContext = context.applicationContext ?: context
        if (appContext.packageName != PACKAGE) return
        if (!Preferences.batteryAutoPowerSaveEnabled() && !Preferences.exitPowerSaveWhenCharging()) return
        var next: BatteryMonitor? = null
        runCatching {
            BatteryMonitor(appContext).also {
                next = it
                it.register()
                monitor = it
            }
        }.onSuccess { DebugLog.i(TAG, "battery monitor registered") }
            .onFailure {
                next?.close()
                DebugLog.hookFailed(TAG, "register battery monitor", it)
            }
    }

    @Synchronized
    override fun onPrepareHotReload() {
        monitor?.close()
        monitor = null
    }
}

/** Battery delivery, provider IO and reconciliation share one lifecycle-owned worker. */
private class BatteryMonitor(private val context: Context) {
    @Volatile private var closed = false
    private var receiverRegistered = false
    private var observerRegistered = false
    private var observedUserId: Int? = null
    private val connectionTracker = ChargerConnectionTracker()
    private var lastSeenCharging = false
    private var readFailureCount = 0
    private var lastSample: Sample? = null
    private var lastConfig: Config? = null
    private var lastUserId: Int? = null
    private var reportedFailure = false
    private var unplugEnablePending = false
    private val thread = HandlerThread("HT-PowerSave").apply { start() }
    private val handler = Handler(thread.looper)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val reconciler = PowerSaveReconciler(
        backend = object : PowerSaveReconciler.Backend {
            override fun read(userId: Int) = PowerSaveReconciler.State(
                nativeEnabled = PowerSaveSetter.nativeEnabled(context, userId),
                frameworkEnabled = powerManager?.isPowerSaveMode ?: false,
            )
            override fun requestNative(target: PowerSaveReconciler.Target) {
                if (closed) return
                PowerSaveSetter.requestNative(context, target.userId, target.enabled)
                DebugLog.i(TAG, "native request dispatched enabled=${target.enabled} user=${target.userId}")
            }
            override fun clearFramework(): Boolean {
                // The verified service rejects this API while powered. Native exit owns that path.
                if (closed || lastSeenCharging || powerManager == null || !PowerSaveSetter.available) return false
                val accepted = PowerSaveSetter.set(powerManager, false)
                DebugLog.d(TAG, "framework cleanup request accepted=$accepted")
                return accepted
            }
        },
        onFailure = { operation, failure ->
            if (failure != null) DebugLog.hookFailed(TAG, operation, failure)
            else DebugLog.w(TAG, operation)
        },
    )

    private val recheck = Runnable { evaluateSafely("recheck") }
    private val settingChanged = Runnable { evaluateSafely("settings") }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            evaluateSafely(intent.action ?: "battery")
        }
    }
    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (closed) return
            handler.removeCallbacks(settingChanged)
            handler.postDelayed(settingChanged, 100L)
        }
    }

    @Synchronized
    fun register() {
        check(!closed) { "Cannot register a closed battery monitor" }
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(ACTION_USER_SWITCHED)
            }, null, handler, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        handler.post { evaluateSafely("register") }
    }

    @Synchronized
    private fun observeUser(userId: Int) {
        if (closed) return
        if (observedUserId == userId) return
        if (observerRegistered) context.contentResolver.unregisterContentObserver(settingsObserver)
        for (uri in listOf(
            Settings.System.getUriFor(PowerSaveSetter.MODE_KEY),
            Settings.Global.getUriFor("low_power"),
        )) {
            context.contentResolver.registerContentObserver(
                uri.buildUpon().encodedAuthority("$userId@${uri.encodedAuthority}").build(),
                false, settingsObserver,
            )
            observerRegistered = true
        }
        observedUserId = userId
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            receiverRegistered = false
            HookFailurePolicy.open(TAG, "unregister battery receiver", Unit) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: IllegalArgumentException) {
                    // Context may already have released its registrations during host teardown.
                    DebugLog.d(TAG, "battery receiver already unregistered")
                }
            }
        }
        if (observerRegistered) {
            observerRegistered = false
            HookFailurePolicy.open(TAG, "unregister settings observer", Unit) {
                context.contentResolver.unregisterContentObserver(settingsObserver)
            }
        }
        observedUserId = null
        thread.quitSafely()
    }

    private fun evaluateSafely(trigger: String) {
        if (closed) return
        handler.removeCallbacks(recheck)
        var delay: Long? = null
        try {
            delay = evaluate(trigger)
            readFailureCount = 0
        } catch (t: Throwable) {
            DebugLog.hookFailed(TAG, "evaluate $trigger", t)
            // Bound transient preparation retries; never maintain a charging hold.
            readFailureCount++
            delay = when {
                readFailureCount <= 3 -> 1_000L
                else -> null
            }
        } finally {
            if (!closed && delay != null) handler.postDelayed(recheck, delay)
        }
    }

    private fun evaluate(trigger: String): Long? {
        // Always use the latest sticky, including for queued broadcasts: their Intent may describe
        // a connection that has already changed again before this worker got to it.
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val raw = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (raw < 0 || scale <= 0 || raw > scale) return null
        val sample = Sample(
            (raw.toLong() * 100 / scale).toInt(),
            sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        )
        lastSeenCharging = sample.charging
        val config = Config(
            Preferences.batteryAutoPowerSaveEnabled(),
            Preferences.batteryAutoPowerSaveThreshold(),
            Preferences.exitPowerSaveWhenCharging(),
        )
        val userId = PowerSaveSetter.currentUser()
        val justPlugged = connectionTracker.update(
            sample.charging, disconnectedEvent = trigger == Intent.ACTION_POWER_DISCONNECTED,
        )
        val justUnplugged = connectionTracker.justDisconnected
        val changed = config != lastConfig || userId != lastUserId
        if (changed) {
            reconciler.cancel()
            unplugEnablePending = false
        }
        HookFailurePolicy.open(TAG, "observe foreground user", Unit) { observeUser(userId) }
        var desired = BatteryAutoPowerSavePolicy.target(
            config.autoEnable, config.threshold, sample.charging, config.exitCharging,
            sample.level, justPlugged, justUnplugged,
        )
        if (justUnplugged && desired == true) unplugEnablePending = true
        if (desired == null && unplugEnablePending && reconciler.pending && !sample.charging &&
            config.exitCharging && sample.level <= config.threshold
        ) desired = true
        if (desired == false) {
            // One command for the connection boundary. No persistent charging enforcement.
            reconciler.cancel()
            unplugEnablePending = false
            lastSample = sample
            lastConfig = config
            lastUserId = userId
            PowerSaveSetter.requestNative(context, userId, false)
            DebugLog.i(TAG, "connection power-save exit dispatched plugged=${sample.charging} level=${sample.level} user=$userId")
            return null
        }
        if (desired == null) {
            reconciler.cancel()
            unplugEnablePending = false
        } else {
            reconciler.aim(
                PowerSaveReconciler.Target(userId, true),
                retryExhausted = sample != lastSample || changed,
            )
        }
        lastSample = sample
        lastConfig = config
        lastUserId = userId
        val result = reconciler.step(SystemClock.elapsedRealtime())
        DebugLog.d(TAG, "evaluate[$trigger] level=${sample.level} plugged=${sample.charging} desired=$desired result=$result")
        when (result) {
            is PowerSaveReconciler.Result.Confirmed -> {
                if (result.changed) DebugLog.i(TAG, "power-save state confirmed enabled=${result.target.enabled} native=${result.state.nativeEnabled} framework=${result.state.frameworkEnabled} level=${sample.level}")
                reportedFailure = false
                unplugEnablePending = false
            }
            is PowerSaveReconciler.Result.Failed -> if (!reportedFailure) {
                reportedFailure = true
                DebugLog.w(TAG, "power-save state NOT confirmed target=${result.target} observed=${result.state}; retries exhausted")
            }
            else -> Unit
        }
        return when {
            result is PowerSaveReconciler.Result.Waiting -> result.delayMs
            else -> null
        }
    }

    private data class Sample(val level: Int, val charging: Boolean)
    private data class Config(val autoEnable: Boolean, val threshold: Int, val exitCharging: Boolean)
    private companion object {
        const val TAG = "BatteryAutoPowerSave"
        const val ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED"
    }
}
