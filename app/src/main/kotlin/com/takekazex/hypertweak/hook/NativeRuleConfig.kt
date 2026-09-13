package com.takekazex.hypertweak.hook

import android.content.Context
import com.takekazex.hypertweak.util.DebugLog
import java.io.File

/**
 * Publishes native rule flags to the launcher-side payload.
 *
 * LSPosed injects only the *native* payload into `com.miui.home`: the launcher's package carries no
 * dex, so the module's Java never runs there and the payload cannot read the module's preferences.
 * The switches therefore reach it as a small file that the payload polls, which also means a change
 * takes effect without restarting the launcher.
 *
 * The launcher runs as `platform_app`. Probing on OS4.0.0.25 showed it can read shared external
 * storage but not app-private storage, the app-specific external directory (`Android/data/<pkg>` is
 * hidden from every other UID) or `/data/local/tmp`. Writing the shared copy through MediaStore is
 * not usable either: MediaProvider renames the entry to `hypertweak_native.conf.txt`, so the
 * payload's path never appears.
 *
 * `Android/media/<pkg>` is therefore the channel: it is the app's own directory on shared storage,
 * so publishing needs no permission and leaves nothing in the user's Downloads listing.
 *
 * Failures are logged, never thrown: being unable to publish a flag must not break the settings
 * screen.
 */
object NativeRuleConfig {
    const val KEY_HIDE_RECENTS_CLEAR = "hide_recents_clear"
    private const val FILE_NAME = "hypertweak_native.conf"

    /** Writes the current value of every native rule to each supported channel. */
    @Suppress("DEPRECATION")
    fun publish(context: Context, hideRecentsClearButton: Boolean) {
        val contents = buildString {
            append(KEY_HIDE_RECENTS_CLEAR)
            append('=')
            append(if (hideRecentsClearButton) '1' else '0')
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
