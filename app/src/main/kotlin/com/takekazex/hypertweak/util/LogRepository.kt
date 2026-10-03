package com.takekazex.hypertweak.util

import com.takekazex.hypertweak.hook.Preferences

/** One snapshot for the log page and diagnostic provider, with explicit source availability. */
object LogRepository {
    data class Snapshot(val records: List<LogRecord>, val lsposedStatus: LsposedLogReader.Status)

    fun read(includeLsposed: Boolean = true): Snapshot {
        val app = LogCodec.parse(Preferences.getDebugLog())
        val framework = if (includeLsposed) LsposedLogReader.read()
            else LsposedLogReader.Result(emptyList(), LsposedLogReader.Status.ROOT_UNAVAILABLE)
        val merged = (app + framework.records).distinctBy {
            listOf(it.time, it.pid, it.process, it.scope, it.message, it.stack)
        }.sortedByDescending { it.time }.mapIndexed { index, record -> record.copy(index = index) }
        return Snapshot(merged, framework.status)
    }

    fun export(snapshot: Snapshot, maxCharacters: Int = Int.MAX_VALUE): String = buildString {
        append("# HyperTweak logs\n${DebugLog.sessionHeader()}\n")
        append("LSPosed=${snapshot.lsposedStatus}\n---\n")
        for (record in snapshot.records) {
            val line = LogCodec.encode(record)
            if (length.toLong() + line.length + 1 > maxCharacters) {
                append("# truncated; use dump for the full file export\n")
                break
            }
            append(line).append('\n')
        }
    }
}
