package com.takekazex.hypertweak.util

import androidx.core.net.toUri

/**
 * Cross-process channel for the debug-log dump interface.
 *
 * App-owned logs and module-identified LSPosed records use the same repository as the log page.
 * Reading LSPosed files requires root granted to the app; exports include source availability.
 * The provider allows only the module UID, root and shell diagnostic callers.
 */
object LogDumpChannel {
    const val AUTHORITY = "com.takekazex.hypertweak.logdump"
    const val URI_PATH = "log"

    /** Writes the aggregated log to the app files dir (`logs/latest.txt` + a stamped copy) and
     *  returns the written paths plus a short preview. The full text stays in the file. */
    const val METHOD_DUMP = "dump"

    /** Returns bounded newest records in the Bundle (no file write); dump exports the full snapshot. */
    const val METHOD_GET = "get"

    const val KEY_PATH = "path"
    const val KEY_FILE = "file"
    const val KEY_LENGTH = "length"
    const val KEY_PREVIEW = "preview"
    const val KEY_DATA = "data"

    fun uri() = "content://$AUTHORITY/$URI_PATH".toUri()
}
