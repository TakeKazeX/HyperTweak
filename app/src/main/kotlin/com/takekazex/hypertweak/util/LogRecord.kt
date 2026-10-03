package com.takekazex.hypertweak.util

/** Shared wire format for the app, hook processes, diagnostic export and LSPosed reader. */
data class LogRecord(
    val time: String,
    val level: String,
    val pid: String,
    val scope: String,
    val event: String,
    val message: String,
    val stack: String = "",
    val process: String = "app",
    val source: String = "module",
    val index: Int = 0
) {
    val isError: Boolean get() = level == "E" || level == "F"
    val isWarning: Boolean get() = level == "W"
    val isHook: Boolean get() = event.startsWith("HOOK")
    val isHookFailed: Boolean get() = event == "HOOK_FAILED"
    val id: String get() = "$index-$source-$time-$pid-$scope"
}

object LogCodec {
    const val SEPARATOR = "\u001f"
    private val legacy = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d) ([VDIWEF])/(\d+) \[(.+?)] (.*)$""")

    fun event(message: String, failed: Boolean = false): String = when {
        message.startsWith("HOOK_FAILED") -> "HOOK_FAILED"
        failed -> "FAILED"
        message.startsWith("HOOK_OK") -> "HOOK_OK"
        message.startsWith("HOOK_SKIPPED") -> "HOOK_SKIPPED"
        message.startsWith("REPEATED") -> "REPEATED"
        else -> "INFO"
    }

    fun encode(record: LogRecord): String = listOf(
        "v3", record.time, record.level, record.pid, record.scope, record.event,
        record.message, record.stack, record.process, record.source
    ).joinToString(SEPARATOR) { escape(it) }

    fun decode(line: String): LogRecord? {
        if (line.startsWith("v2$SEPARATOR") || line.startsWith("v3$SEPARATOR")) {
            val p = line.split(SEPARATOR).map(::unescape)
            if (p.size < 8 || (p[0] == "v3" && p.size != 10)) return null
            return LogRecord(p[1], p[2], p[3], p[4], p[5], p[6], p[7],
                p.getOrElse(8) { "unknown" }, p.getOrElse(9) { "module" })
        }
        val m = legacy.matchEntire(line) ?: return null
        return LogRecord(m.groupValues[1], m.groupValues[2], m.groupValues[3],
            m.groupValues[4], event(m.groupValues[5]), m.groupValues[5], process = "unknown")
    }

    fun parse(raw: String): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        raw.lineSequence().forEach { line ->
            val record = decode(line)
            if (record != null) records += record
            else if (records.isNotEmpty() && (line.startsWith("\tat ") ||
                    line.startsWith("Caused by:") || line.startsWith("Suppressed:"))) {
                val last = records.removeAt(records.lastIndex)
                records += last.copy(stack = listOf(last.stack, line).filter(String::isNotBlank).joinToString("\n"))
            }
        }
        return records
    }

    fun escape(value: String): String = value.replace("\\", "\\\\")
        .replace("\n", "\\n").replace("\r", "\\r").replace(SEPARATOR, " ")

    fun unescape(value: String): String = buildString {
        var escaped = false
        for (ch in value) {
            if (escaped) {
                append(when (ch) { 'n' -> '\n'; 'r' -> '\r'; else -> ch })
                escaped = false
            } else if (ch == '\\') escaped = true else append(ch)
        }
        if (escaped) append('\\')
    }

    /** Keep complete newest records; an oversize single entry never exceeds the cap. */
    fun boundedTail(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val boundary = text.indexOf('\n', text.length - limit)
        return if (boundary < 0) "" else text.substring(boundary + 1)
    }
}
