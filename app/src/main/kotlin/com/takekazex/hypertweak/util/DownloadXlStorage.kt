package com.takekazex.hypertweak.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Root-only inspection and cleanup of the Download Manager's exact shared-storage log directory. */
object DownloadXlStorage {
    const val ROOT_PATH = "/storage/emulated/0/.xlDownload"

    /**
     * Returns null when neither the root shell nor a direct filesystem check can establish the
     * state. A false result is only produced by a successful root-shell check, avoiding a scoped
     * storage denial being mistaken for an absent directory.
     */
    suspend fun directoryExists(): Boolean? = withContext(Dispatchers.IO) {
        val quotedPath = shellQuote(ROOT_PATH)
        val rootResult = runRootScript(
            "if [ -d $quotedPath ]; then echo PRESENT; else echo ABSENT; fi"
        )
        when (rootResult?.marker()) {
            "PRESENT" -> true
            "ABSENT" -> false
            else -> if (runCatching { File(ROOT_PATH).isDirectory }.getOrDefault(false)) {
                true
            } else {
                null
            }
        }
    }

    /**
     * Deletes only [ROOT_PATH] and reports whether it is gone. The caller must obtain explicit
     * confirmation before invoking this method.
     */
    suspend fun deleteDirectory(): Boolean? = withContext(Dispatchers.IO) {
        val quotedPath = shellQuote(ROOT_PATH)
        val rootResult = runRootScript(
            "if [ -d $quotedPath ]; then rm -rf $quotedPath; fi\n" +
                "if [ ! -e $quotedPath ]; then echo DELETED; else echo PRESENT; fi"
        )
        when (rootResult?.marker()) {
            "DELETED" -> true
            "PRESENT" -> false
            else -> null
        }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun runRootScript(script: String): RootResult? {
        return runCatching {
            val process = Runtime.getRuntime().exec("su")
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(script)
                writer.write("\nexit\n")
                writer.flush()
            }
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            RootResult(
                exitCode = process.exitValue(),
                stdout = process.inputStream.bufferedReader().use { it.readText() },
                stderr = process.errorStream.bufferedReader().use { it.readText() }
            )
        }.getOrNull()
    }

    private data class RootResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        fun marker(): String? {
            if (exitCode != 0) return null
            return stdout.lineSequence()
                .map(String::trim)
                .lastOrNull { it.isNotEmpty() }
        }
    }
}
