package com.takekazex.hypertweak.util

import android.util.Log

/** Storage reports diagnostics without constructing the logger or reading its own settings again. */
internal object SettingsDiagnostics {
    @Volatile private var sink: ((Int, String, String, Throwable?) -> Unit)? = null
    private val reporting = ThreadLocal<Boolean>()

    fun bind(writer: (Int, String, String, Throwable?) -> Unit) { sink = writer }
    fun d(scope: String, message: String) = report(Log.DEBUG, scope, message, null)
    fun i(scope: String, message: String) = report(Log.INFO, scope, message, null)
    fun w(scope: String, message: String, error: Throwable? = null) = report(Log.WARN, scope, message, error)
    fun e(scope: String, message: String, error: Throwable? = null) = report(Log.ERROR, scope, message, error)

    private fun report(level: Int, scope: String, message: String, error: Throwable?) {
        val writer = sink
        if (writer == null || reporting.get() == true) {
            fallback(level, scope, message, error)
            return
        }
        reporting.set(true)
        try { writer(level, scope, message, error) }
        catch (_: Throwable) { fallback(level, scope, message, error) }
        finally { reporting.remove() }
    }

    private fun fallback(level: Int, scope: String, message: String, error: Throwable?) {
        // A storage/bootstrap/logger failure must not initialize Preferences through DebugLog.
        runCatching { Log.println(level, "HyperTweak", "$scope: $message" +
            (error?.let { "\n${Log.getStackTraceString(it)}" } ?: "")) }
    }
}
