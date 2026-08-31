package com.takekazex.hypertweak.util

/**
 * Cross-process channel for the debug-log dump interface.
 *
 * The module's per-process debug logs live in the LSPosed daemon's remote prefs, which the settings
 * UI already reads. [LogDumpProvider] exposes the aggregated log over a content provider so an agent
 * (adb / scripting) can pull it without opening the app. This object holds the authority, the [call]
 * method names and the result Bundle keys.
 */
object LogDumpChannel {
    const val AUTHORITY = "com.takekazex.hypertweak.logdump"
    const val URI_PATH = "log"

    /** Writes the aggregated log to the app files dir (`logs/latest.txt` + a stamped copy) and
     *  returns the written paths plus a short preview. The full text stays in the file. */
    const val METHOD_DUMP = "dump"

    /** Returns the aggregated log text in the result Bundle (no file write). */
    const val METHOD_GET = "get"

    const val KEY_PATH = "path"
    const val KEY_FILE = "file"
    const val KEY_LENGTH = "length"
    const val KEY_PREVIEW = "preview"
    const val KEY_DATA = "data"

    fun uri() = android.net.Uri.parse("content://$AUTHORITY/$URI_PATH")
}
