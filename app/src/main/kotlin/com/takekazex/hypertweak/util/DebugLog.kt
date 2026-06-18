package com.takekazex.hypertweak.util

import android.os.Process
import android.util.Log
import com.takekazex.hypertweak.hook.Preferences
import io.github.libxposed.api.XposedInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object DebugLog {
    private const val TAG = "HyperTweak"
    private const val FIELD_SEPARATOR = "\u001F"
    private const val FLUSH_DELAY_MS = 750L
    private const val MAX_PENDING_LINES = 64
    private val formatter = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val pendingLines = ConcurrentLinkedQueue<String>()
    private val flushExecutor by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "HyperTweakDebugLog").apply { isDaemon = true }
        }
    }

    @Volatile
    private var xposed: XposedInterface? = null

    @Volatile
    private var pendingFlush: ScheduledFuture<*>? = null

    fun bindXposed(interfaceRef: XposedInterface) {
        xposed = interfaceRef
        d("DebugLog", "bound LSPosed logger api=${interfaceRef.apiVersion}")
    }

    fun d(scope: String, message: String) {
        write(Log.DEBUG, scope, message, null)
    }

    fun w(scope: String, message: String, throwable: Throwable? = null) {
        write(Log.WARN, scope, message, throwable)
    }

    fun e(scope: String, message: String, throwable: Throwable? = null) {
        write(Log.ERROR, scope, message, throwable)
    }

    fun hookRegistered(scope: String, target: String) {
        d(scope, "HOOK_OK target=$target")
    }

    fun hookFailed(scope: String, target: String, throwable: Throwable? = null) {
        e(scope, "HOOK_FAILED target=$target", throwable)
    }

    fun hookSkipped(scope: String, target: String, reason: String) {
        w(scope, "HOOK_SKIPPED target=$target reason=$reason")
    }

    private fun write(priority: Int, scope: String, message: String, throwable: Throwable?) {
        val fullMessage = "$scope: $message"
        when (priority) {
            Log.ERROR -> Log.e(TAG, fullMessage, throwable)
            Log.WARN -> Log.w(TAG, fullMessage, throwable)
            else -> Log.d(TAG, fullMessage)
        }

        runCatching {
            val logger = xposed
            if (logger != null) {
                if (throwable != null) {
                    logger.log(priority, TAG, fullMessage, throwable)
                } else {
                    logger.log(priority, TAG, fullMessage)
                }
            }
        }

        enqueueLine(formatLine(priority, scope, message, throwable), priority >= Log.WARN)
    }

    private fun enqueueLine(line: String, urgent: Boolean) {
        pendingLines.offer(line)
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
            flushExecutor.schedule({ flushPendingLines() }, delay, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun flushPendingLines() {
        val lines = mutableListOf<String>()
        while (true) {
            val line = pendingLines.poll() ?: break
            lines.add(line)
        }
        if (lines.isEmpty()) return
        runCatching {
            Preferences.appendDebugLogs(lines)
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
