package com.takekazex.hypertweak.util

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.hook.Preferences
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object DebugLog {
    private const val TAG = "HyperTweak"
    private const val FIELD_SEPARATOR = "\u001F"
    private const val FLUSH_DELAY_MS = 750L
    private const val MAX_PENDING_LINES = 64

    /** Hard cap on the in-memory queue so a hot path (DEBUG flood) can't grow it without bound. */
    private const val MAX_PENDING_QUEUE = 512

    /** Default threshold: drop VERBOSE/DEBUG, keep INFO and above. */
    const val DEFAULT_LEVEL = Log.INFO

    private val formatter = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val pendingLines = ConcurrentLinkedQueue<String>()

    @Volatile
    private var sessionHeaderEmitted = false

    @Volatile
    private var xposed: XposedInterface? = null

    @Volatile
    private var pendingFlush: ScheduledFuture<*>? = null

    @Volatile
    private var flushExecutor: ScheduledExecutorService? = null

    @Volatile
    private var processTag: String = "app"

    fun setProcessTag(tag: String) {
        processTag = tag
    }

    /**
     * Drops stale logs when the session changes (app update / reinstall / device reboot) so
     * records from separate runtimes don't pile up together. Requires [Preferences] to be ready.
     */
    fun ensureSession() {
        runCatching { Preferences.rotateLogSessionIfNeeded(sessionToken()) }
        emitSessionHeader()
    }

    /**
     * Writes a one-time per-process session header (device / build / module version) so the log has
     * the exact ROM and module build that produced it — the key fact for triaging OTA-dependent
     * breakage. Emitted at INFO once per process session; an explicit higher log level drops it,
     * but the log-dump interface prepends a fresh header regardless.
     */
    private fun emitSessionHeader() {
        if (sessionHeaderEmitted) return
        sessionHeaderEmitted = true
        i("Session", sessionHeader())
    }

    fun sessionHeader(): String {
        val hyperOs = runCatching {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("get", String::class.java, String::class.java)
                .invoke(null, "ro.miui.ui.version.name", "") as String
        }.getOrNull().orEmpty()
        return buildString {
            append("device=${android.os.Build.DEVICE} model=${android.os.Build.MODEL} android=${android.os.Build.VERSION.RELEASE}(SDK${android.os.Build.VERSION.SDK_INT})")
            append(" build=${android.os.Build.ID}/${android.os.Build.DISPLAY}")
            if (hyperOs.isNotBlank()) append(" hyperos=$hyperOs")
            append(" fingerprint=${android.os.Build.FINGERPRINT}")
            append(" module=v${BuildConfig.VERSION_CODE} process=$processTag")
        }
    }

    private fun sessionToken(): String {
        val bootId = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: ((System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000L).toString()
        return "v${BuildConfig.VERSION_CODE}_$bootId"
    }

    fun bindXposed(interfaceRef: XposedInterface) {
        xposed = interfaceRef
        d("DebugLog", "bound LSPosed logger api=${interfaceRef.apiVersion}")
    }

    fun prepareForHotReload() {
        pendingFlush?.cancel(false)
        flushPendingLines()
        flushExecutor?.shutdownNow()
        flushExecutor = null
        xposed = null
    }

    fun d(scope: String, message: String) {
        write(Log.DEBUG, scope, message, null)
    }

    fun i(scope: String, message: String) {
        write(Log.INFO, scope, message, null)
    }

    fun w(scope: String, message: String, throwable: Throwable? = null) {
        write(Log.WARN, scope, message, throwable)
    }

    fun e(scope: String, message: String, throwable: Throwable? = null) {
        write(Log.ERROR, scope, message, throwable)
    }

    fun hookRegistered(scope: String, target: String) {
        i(scope, "HOOK_OK target=$target")
    }

    fun hookFailed(scope: String, target: String, throwable: Throwable? = null) {
        e(scope, "HOOK_FAILED target=$target", throwable)
    }

    fun hookSkipped(scope: String, target: String, reason: String) {
        w(scope, "HOOK_SKIPPED target=$target reason=$reason")
    }

    private fun currentThreshold(): Int {
        // Per-process override wins, so one process can be debugged at DEBUG without flooding the
        // rest. Falls back to the global level.
        val perProcess = runCatching { Preferences.logLevelFor(processTag) }.getOrNull()
        if (perProcess != null) return perProcess
        if (!Preferences.isInitialized) return DEFAULT_LEVEL
        return runCatching { Preferences.getInt(Preferences.KEY_LOG_LEVEL, DEFAULT_LEVEL) }
            .getOrDefault(DEFAULT_LEVEL)
    }

    private fun write(priority: Int, scope: String, message: String, throwable: Throwable?) {
        if (priority < currentThreshold()) return

        val fullMessage = "$scope: $message"
        when (priority) {
            Log.ERROR -> Log.e(TAG, fullMessage, throwable)
            Log.WARN -> Log.w(TAG, fullMessage, throwable)
            Log.INFO -> Log.i(TAG, fullMessage)
            else -> Log.d(TAG, fullMessage)
        }

        // Never let a preference hiccup crash the logger: a failure inside a hook callback (e.g.
        // while attaching a hooker) is itself logged via this path, and a throw here would swallow
        // the very diagnostic we are trying to record (and propagate up into the host). If the
        // record toggle can't be read, default to recording so the failure is preserved.
        val recordLogs = runCatching { Preferences.getBoolean(Preferences.KEY_RECORD_LOGS, true) }
            .getOrDefault(true)
        if (recordLogs) {
            enqueueLine(formatLine(priority, scope, message, throwable), priority >= Log.WARN)
        }
        forwardToXposed(priority, fullMessage, throwable)
    }

    private fun forwardToXposed(priority: Int, fullMessage: String, throwable: Throwable?) {
        val logger = xposed ?: return
        val task = Runnable {
            runCatching {
                if (throwable != null) {
                    logger.log(priority, TAG, fullMessage, throwable)
                } else {
                    logger.log(priority, TAG, fullMessage)
                }
            }
        }
        val scheduled = runCatching {
            getFlushExecutor().schedule(task, 0L, TimeUnit.MILLISECONDS)
        }.getOrNull()
        if (scheduled == null) task.run()
    }

    private fun enqueueLine(line: String, urgent: Boolean) {
        pendingLines.offer(line)
        // Bound memory under a DEBUG flood: drop the oldest once the queue exceeds the cap.
        if (pendingLines.size > MAX_PENDING_QUEUE) {
            pendingLines.poll()
        }
        scheduleFlush(urgent || pendingLines.size >= MAX_PENDING_LINES)
    }

    @Synchronized
    private fun scheduleFlush(immediate: Boolean) {
        val current = pendingFlush
        if (!immediate && current != null && !current.isDone) return
        if (immediate && current != null && !current.isDone) {
            current.cancel(false)
        }

        val delay = if (immediate) 0L else FLUSH_DELAY_MS
        pendingFlush = runCatching {
            getFlushExecutor().schedule({ flushPendingLines() }, delay, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    @Synchronized
    private fun getFlushExecutor(): ScheduledExecutorService {
        val current = flushExecutor
        if (current != null && !current.isShutdown) return current

        return Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "HyperTweakDebugLog").apply { isDaemon = true }
        }.also { flushExecutor = it }
    }

    private fun flushPendingLines() {
        val lines = mutableListOf<String>()
        while (true) {
            val line = pendingLines.poll() ?: break
            lines.add(line)
        }
        if (lines.isEmpty()) return
        runCatching {
            Preferences.appendDebugLogs(processTag, lines)
        }
    }

    private fun formatLine(priority: Int, scope: String, message: String, throwable: Throwable?): String {
        val level = when (priority) {
            Log.ERROR -> "E"
            Log.WARN -> "W"
            Log.INFO -> "I"
            else -> "D"
        }
        val time = formatter.get()!!.format(Date())
        val stack = throwable?.let { Log.getStackTraceString(it).trimEnd() }.orEmpty()
        return listOf(
            "v2",
            escape(time),
            escape(level),
            Process.myPid().toString(),
            escape(scope),
            escape(eventFrom(message, throwable)),
            escape(message),
            escape(stack)
        ).joinToString(FIELD_SEPARATOR)
    }

    private fun eventFrom(message: String, throwable: Throwable?): String {
        return when {
            throwable != null -> "FAILED"
            message.startsWith("HOOK_OK") -> "HOOK_OK"
            message.startsWith("HOOK_FAILED") -> "HOOK_FAILED"
            message.startsWith("HOOK_SKIPPED") -> "HOOK_SKIPPED"
            "failed" in message.lowercase(Locale.US) -> "FAILED"
            "not found" in message.lowercase(Locale.US) -> "MISSING"
            "skip" in message.lowercase(Locale.US) -> "SKIPPED"
            "hooked" in message.lowercase(Locale.US) -> "HOOK_OK"
            "registered" in message.lowercase(Locale.US) -> "OK"
            "loaded" in message.lowercase(Locale.US) -> "OK"
            else -> "INFO"
        }
    }

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace(FIELD_SEPARATOR, " ")
    }
}
