package com.takekazex.hypertweak.hook

import android.content.Context
import com.takekazex.hypertweak.util.DebugLog
import java.io.File

/**
 * Publishes native rule flags as a file.
 *
 * This is **not** the channel the launcher-side rules read — [NativeRules.applyRuleSwitches] is,
 * because the file is unreadable from the launcher process (see below). The file is kept as a
 * human- and script-inspectable record of the published values and as a manual override for
 * debugging, and the payload still consults it at initialization when no push has arrived.
 *
 * The launcher runs as `platform_app_36` with an ordinary app uid. It is neither the file's owner
 * nor in its `media_rw` group, and scoped storage refuses it the module's `Android/media`
 * directory, so the read fails outright. Writing the shared copy through MediaStore is not usable
 * either: MediaProvider names the entry `hypertweak_native.conf.txt`, so the payload's path never
 * appears.
 *
 * `Android/media/<pkg>` is therefore where the file goes: it is the app's own directory on shared
 * storage, so publishing needs no permission and leaves nothing in the user's Downloads listing.
 *
 * Failures are logged, never thrown: being unable to write the record must not break the settings
 * screen.
 */
object NativeRuleConfig {
    const val KEY_HIDE_RECENTS_CLEAR = "hide_recents_clear"
    const val KEY_OPENED_FOLDER_COLUMNS = "opened_folder_columns"
    const val KEY_CONTEXTUAL_SEARCH_LONG_PRESS = "contextual_search_long_press"
    private const val FILE_NAME = "hypertweak_native.conf"

    /** Writes the current value of every native rule to each supported channel. */
    @Suppress("DEPRECATION")
    fun publish(
        context: Context,
        hideRecentsClearButton: Boolean,
        openedFolderColumns: Int = Preferences.openedFolderColumns(),
        contextualSearchLongPress: Boolean = Preferences.contextualSearchLongPress()
    ) {
        val contents = buildString {
            append(KEY_HIDE_RECENTS_CLEAR)
            append('=')
            append(if (hideRecentsClearButton) '1' else '0')
            append('\n')
            append(KEY_OPENED_FOLDER_COLUMNS)
            append('=')
            append(
                openedFolderColumns.coerceIn(
                    Preferences.MIN_OPENED_FOLDER_COLUMNS,
                    Preferences.MAX_OPENED_FOLDER_COLUMNS
                )
            )
            append('\n')
            append(KEY_CONTEXTUAL_SEARCH_LONG_PRESS)
            append('=')
            append(if (contextualSearchLongPress) '1' else '0')
            append('\n')
        }
        context.getExternalMediaDirs().orEmpty().forEach { directory ->
            val file = File(directory, FILE_NAME)
            runCatching {
                val parent = file.parentFile ?: error("config directory is missing")
                parent.mkdirs()
                // Publish by rename so the launcher never observes a truncated or half-written
                // config while its polling thread is reading the file.
                val temporary = File(parent, "$FILE_NAME.tmp")
                temporary.writeText(contents)
                check(temporary.renameTo(file)) { "could not publish ${file.absolutePath}" }
            }.onFailure {
                DebugLog.d("NativeRuleConfig", "cannot publish to ${file.absolutePath}: ${it.message}")
            }
        }
    }
}
