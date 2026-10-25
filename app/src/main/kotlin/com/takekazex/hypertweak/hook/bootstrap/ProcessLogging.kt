package com.takekazex.hypertweak.hook.bootstrap

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.AppLogSink
import com.takekazex.hypertweak.util.DebugLog

/** Wires settings and app persistence at the process boundary, outside the logger and storage. */
internal object ProcessLogging {
    fun bind(app: Boolean = false) {
        val sink = if (app) object : AppLogSink {
            override fun rotateSession(token: String) = Preferences.appLogs.rotateLogSessionIfNeeded(token)
            override fun append(process: String, lines: List<String>) = Preferences.appLogs.appendDebugLogs(process, lines)
        } else null
        if (app) com.takekazex.hypertweak.util.LogRepository.bindAppRecords(Preferences.appLogs::getDebugLog)
        DebugLog.bindStorage({ Preferences.logLevel(DebugLog.DEFAULT_LEVEL) }, sink)
    }
}
