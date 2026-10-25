package com.takekazex.hypertweak.util

/** Root broadcast is needed because LSPosed does not expose a regular launcher activity. */
internal object LsposedManagerLauncher {
    fun open() {
        Thread {
            runCatching {
                Runtime.getRuntime().exec("su").outputStream.bufferedWriter().use { writer ->
                    writer.write("am broadcast -a android.telephony.action.SECRET_CODE -d android_secret_code://5776733\nexit\n")
                    writer.flush()
                }
            }.onFailure { DebugLog.w("Shortcut", "LSPosed manager launch failed", it) }
        }.start()
    }
}
