package com.takekazex.hypertweak

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Binder
import android.os.Process
import com.takekazex.hypertweak.util.LogRepository
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.LogDumpChannel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI/automation-readable debug-log export.
 *
 * App-owned records and module-identified LSPosed records are assembled by LogRepository.
 * Framework file access requires root permission granted to the app; its status is included in
 * every export. App records stay readable when the framework source is unavailable. This is
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
        LogDumpChannel.METHOD_DUMP -> if (isTrustedCaller()) dump(arg) else null
        LogDumpChannel.METHOD_GET -> Bundle().apply {
            if (isTrustedCaller()) putString(LogDumpChannel.KEY_DATA, exportText(128_000))
        }.takeIf { isTrustedCaller() }
        else -> null
    }

    /** Keep the adb shell diagnostic path while denying ordinary third-party apps. */
    private fun isTrustedCaller(): Boolean {
        val uid = Binder.getCallingUid()
        return uid == Process.myUid() || uid == Process.ROOT_UID || uid == Process.SHELL_UID
    }

    /** Aggregated log with a fresh session header, independent of any per-process log level. */
    private fun exportText(maxCharacters: Int = Int.MAX_VALUE): String {
        return LogRepository.export(LogRepository.read(), maxCharacters)
    }

    private fun dump(filename: String?): Bundle {
        val text = exportText()
        val dir = File(requireNotNull(context).filesDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val stamped = File(dir, filename?.takeIf { it.isNotBlank() && !it.contains('/') } ?: "hypertweak-logs-$stamp.txt")
        val latest = File(dir, "latest.txt")
        try {
            stamped.writeText(text)
            latest.writeText(text)
        } catch (t: Exception) {
            DebugLog.e("LogDump", "diagnostic export failed", t)
            return Bundle().apply { putString("error", t.javaClass.simpleName) }
        }
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
