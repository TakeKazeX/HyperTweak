package com.takekazex.hypertweak.util

import com.takekazex.hypertweak.util.platform.NativeRootCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Exact shared-storage paths accepted by the provider hook; never delete without confirmation. */
object DownloadXlStorage {
    const val ROOT_PATH = "/storage/emulated/0/.xlDownload"
    private val paths = listOf(ROOT_PATH, "/sdcard/.xlDownload")

    /** Direct access can prove presence, but scoped-storage denial cannot prove absence. */
    suspend fun directoryExists(): Boolean? = withContext(Dispatchers.IO) {
        resolvePresence(runRootScript(existenceScript(paths)), paths.any {
            runCatching { File(it).isDirectory }.getOrDefault(false)
        })
    }

    /** Only the explicit Delete action may call this; both aliases are checked afterward. */
    suspend fun deleteDirectory(): Boolean? = withContext(Dispatchers.IO) {
        when (runRootScript(deletionScript(paths))) {
            "DELETED" -> !paths.any {
                runCatching { File(it).isDirectory }.getOrDefault(false)
            }
            "PRESENT" -> false
            else -> null
        }
    }

    internal fun resolvePresence(marker: String?, directPresence: Boolean): Boolean? = when {
        marker == "PRESENT" || directPresence -> true
        marker == "ABSENT" -> false
        else -> null
    }

    internal fun existenceScript(targets: List<String>): String =
        "if " + targets.joinToString(" || ") { "[ -d ${NativeRootCommand.quote(it)} ]" } +
            "; then echo PRESENT; else echo ABSENT; fi"

    internal fun deletionScript(targets: List<String>): String {
        val quoted = targets.map(NativeRootCommand::quote)
        return quoted.joinToString("\n") { "if [ -d $it ]; then rm -rf -- $it || exit 1; fi" } +
            "\nif " + quoted.joinToString(" && ") { "[ ! -e $it ] && [ ! -L $it ]" } +
            "; then echo DELETED; else echo PRESENT; fi"
    }

    private fun runRootScript(script: String): String? = runCatching {
        // Includes the first root authorization prompt, with bounded IO and process retirement.
        NativeRootCommand.run(script, maxBytes = 4096, timeoutMs = 60_000)
            .toString(Charsets.UTF_8).lineSequence().map(String::trim)
            .lastOrNull { it.isNotEmpty() }
    }.getOrNull()
}
