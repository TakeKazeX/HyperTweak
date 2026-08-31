package com.takekazex.hypertweak

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.LogDumpChannel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI/automation-readable debug-log export.
 *
 * The module's per-process debug logs live in the LSPosed daemon's remote prefs. This provider runs
 * in the module's own process (auto-started on access) and assembles the aggregated log into one
 * text blob with a session header, either returning it over a [call] or writing it to a file. It is
 * the stable machine interface an agent drives from a shell:
 *
 *   adb shell content call --uri content://com.takekazex.hypertweak.logdump --method dump
 *   → writes logs/latest.txt (+ a stamped copy) under the app files dir, returns the path + preview.
 *   adb pull <returned path>
 *
 * The `data`/`path`/`length`/`preview` key names are stable (see [LogDumpChannel]) so scripts can
 * parse the result without knowing internal formatting.
 */
class LogDumpProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        LogDumpChannel.METHOD_DUMP -> dump(arg)
        LogDumpChannel.METHOD_GET -> Bundle().apply {
            putString(LogDumpChannel.KEY_DATA, exportText())
        }
        else -> null
    }

    /** Aggregated log with a fresh session header, independent of any per-process log level. */
    private fun exportText(): String {
        val log = runCatching { Preferences.getDebugLog() }.getOrDefault("")
        val header = "# HyperTweak debug log export\n${DebugLog.sessionHeader()}\ntime=${System.currentTimeMillis()}"
        return if (log.isBlank()) "$header\n(no debug log recorded)"
        else "$header\n---\n$log"
    }

    private fun dump(filename: String?): Bundle {
        val text = exportText()
        val dir = File(requireNotNull(context).filesDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val stamped = File(dir, filename?.takeIf { it.isNotBlank() && !it.contains('/') } ?: "hypertweak-logs-$stamp.txt")
        val latest = File(dir, "latest.txt")
        runCatching { stamped.writeText(text) }
        runCatching { latest.writeText(text) }
        return Bundle().apply {
            putString(LogDumpChannel.KEY_PATH, latest.absolutePath)
            putString(LogDumpChannel.KEY_FILE, stamped.absolutePath)
            putInt(LogDumpChannel.KEY_LENGTH, text.length)
            putString(LogDumpChannel.KEY_PREVIEW, text.take(400))
        }
    }

    // The rest of the provider surface is unused; the dump travels over [call].
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
