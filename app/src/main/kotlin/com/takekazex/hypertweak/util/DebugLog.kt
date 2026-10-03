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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object DebugLog {
    private const val TAG = "HyperTweak"
    private const val FLUSH_DELAY_MS = 750L

    /** Hard cap on the in-memory queue so a hot path (DEBUG flood) can't grow it without bound. */
    private const val MAX_PENDING_QUEUE = 512

    /** Default threshold: drop VERBOSE/DEBUG, keep INFO and above. */
    const val DEFAULT_LEVEL = Log.INFO

    private val formatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

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
        if (xposed == null) runCatching { Preferences.rotateLogSessionIfNeeded(sessionToken()) }
            .onFailure { runCatching { Log.e(TAG, "log session rotation failed", it) } }
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
        sessionHeaderEmitted = false
        xposed = interfaceRef
        d("DebugLog", "bound LSPosed logger api=${interfaceRef.apiVersion}")
    }

    fun prepareForHotReload() {
        synchronized(this) {
            repeatLimiter.drain(SystemClock.elapsedRealtime(), force = true).forEach(::enqueue)
            pendingFlush?.cancel(false)
            flushPendingLines()
            flushExecutor?.shutdown()
            flushExecutor = null
            xposed = null
        }
    }

    fun d(scope: String, message: String) = write(Log.DEBUG, scope, message, null)
    fun i(scope: String, message: String) = write(Log.INFO, scope, message, null)
    fun w(scope: String, message: String, throwable: Throwable? = null) = write(Log.WARN, scope, message, throwable)
    fun e(scope: String, message: String, throwable: Throwable? = null) = write(Log.ERROR, scope, message, throwable)
    fun hookRegistered(scope: String, target: String) = d(scope, "HOOK_OK target=$target")
    fun hookFailed(scope: String, target: String, throwable: Throwable? = null) = e(scope, "HOOK_FAILED target=$target", throwable)
    fun hookSkipped(scope: String, target: String, reason: String) = w(scope, "HOOK_SKIPPED target=$target reason=$reason")
    fun hookSkippedDebug(scope: String, target: String, reason: String) = d(scope, "HOOK_SKIPPED target=$target reason=$reason")

    private val repeatLimiter = LogRepeatLimiter()
    private data class Pending(val record: LogRecord, val throwable: Throwable?, val logger: XposedInterface?)
    private val queueLock = Any()
    private val records = ArrayDeque<Pending>()
    private var dropped = 0

    private fun currentThreshold(): Int = runCatching {
        if (Preferences.isInitialized) {
            Preferences.getInt(Preferences.KEY_LOG_LEVEL, DEFAULT_LEVEL)
        } else DEFAULT_LEVEL
    }.getOrDefault(DEFAULT_LEVEL)

    private fun write(priority: Int, scope: String, message: String, throwable: Throwable?) {
        // Successful target registration is diagnostic detail. Severity never depends on words
        // like "error" appearing inside a successful operation's description.
        val effective = if (priority == Log.INFO && message.startsWith("HOOK_OK")) Log.DEBUG else priority
        if (effective < currentThreshold()) return
        val record = LogRecord(
            formatter.get()!!.format(Date()), levelName(effective), Process.myPid().toString(), scope,
            LogCodec.event(message, throwable != null), message.take(8000),
            throwable?.let { Log.getStackTraceString(it).trimEnd().take(16000) }.orEmpty(),
            processTag, if (xposed == null) "app" else "LSPosed"
        )
        repeatLimiter.drain(SystemClock.elapsedRealtime()).forEach(::enqueue)
        if (repeatLimiter.accept(record, SystemClock.elapsedRealtime())) enqueue(record, throwable)
    }

    private fun enqueue(record: LogRecord, throwable: Throwable? = null) {
        val priority = when (record.level) { "E", "F" -> Log.ERROR; "W" -> Log.WARN; "I" -> Log.INFO; else -> Log.DEBUG }
        if (record.event == "REPEATED" && priority < currentThreshold()) return
        synchronized(queueLock) {
            if (records.size >= MAX_PENDING_QUEUE) {
                // Preserve warnings/errors preferentially, but never allow an error storm to
                // allocate an unbounded executor queue. Emit the loss count on the next drain.
                val discard = records.indexOfFirst { it.record.level !in listOf("W", "E", "F") }
                if (discard >= 0) records.removeAt(discard) else records.removeFirst()
                dropped++
            }
            records.addLast(Pending(
                if (record.event == "REPEATED") record.copy(time = formatter.get()!!.format(Date())) else record,
                throwable, xposed
            ))
        }
        scheduleFlush(record.level in listOf("W", "E", "F"))
    }

    @Synchronized
    private fun scheduleFlush(immediate: Boolean) {
        val current = pendingFlush
        if (current?.isDone == false) {
            if (!immediate || current.getDelay(TimeUnit.MILLISECONDS) <= 0L) return
            current.cancel(false)
        }
        pendingFlush = runCatching {
            getFlushExecutor().schedule({
                pendingFlush = null
                flushPendingLines()
            }, if (immediate) 0L else FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
        }.getOrElse {
            // The logger's own failures must never re-enter DebugLog.
            runCatching { Log.e(TAG, "log worker unavailable", it) }
            null
        }
    }

    @Synchronized
    private fun getFlushExecutor(): ScheduledExecutorService {
        flushExecutor?.takeUnless { it.isShutdown }?.let { return it }
        return Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "HyperTweakDebugLog").apply { isDaemon = true }
        }.also { executor ->
            flushExecutor = executor
            executor.scheduleWithFixedDelay({
                repeatLimiter.drain(SystemClock.elapsedRealtime()).forEach(::enqueue)
            }, 60L, 60L, TimeUnit.SECONDS)
        }
    }

    @Synchronized
    private fun flushPendingLines() {
        val pending = synchronized(queueLock) {
            val batch = records.toList()
            records.clear()
            if (dropped > 0) {
                val loss = LogRecord(formatter.get()!!.format(Date()), "W", Process.myPid().toString(),
                    "DebugLog", "DROPPED", "log queue overflow: dropped=$dropped", process = processTag)
                dropped = 0
                batch + Pending(loss, null, xposed)
            } else batch
        }
        if (pending.isEmpty()) return
        val localLines = mutableListOf<String>()
        for ((record, throwable, logger) in pending) {
            val priority = when (record.level) { "E", "F" -> Log.ERROR; "W" -> Log.WARN; "I" -> Log.INFO; else -> Log.DEBUG }
            val text = "${record.scope}: ${record.message}"
            runCatching { Log.println(priority, TAG, text + if (record.stack.isBlank()) "" else "\n${record.stack}") }
            if (logger != null) {
                runCatching {
                    if (throwable == null) logger.log(priority, TAG, text)
                    else logger.log(priority, TAG, text, throwable)
                }.onFailure { runCatching { Log.e(TAG, "LSPosed log forwarding failed", it) } }
            } else localLines += LogCodec.encode(record)
        }
        // Hook-side remote preferences are a configuration channel, not a writable log store.
        // LSPosed owns those records; the app reads them through LsposedLogReader.
        if (localLines.isNotEmpty()) runCatching { Preferences.appendDebugLogs(processTag, localLines) }
            .onFailure { runCatching { Log.e(TAG, "app log persistence failed", it) } }
    }

    private fun levelName(priority: Int): String = when (priority) {
        Log.ERROR -> "E"; Log.WARN -> "W"; Log.INFO -> "I"; else -> "D"
    }
}
