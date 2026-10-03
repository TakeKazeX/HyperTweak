package com.takekazex.hypertweak.util

/** Select by module identity, never by a package name mentioned inside another module's message. */
object LsposedLogParser {
    private val head = Regex("""^\[\s*(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+)\s+\d+:\s*(\d+):\s*\d+ ([VDIWEF])/[^ ]+ ] \(([^)]+)\)\[([^]]+)] (.*)$""")
    private const val MODULE = "com.takekazex.hypertweak"
    private const val MAX_RECORDS = 2000
    private const val MAX_STACK = 16000
    private data class Message(val id: String?, var record: LogRecord)

    fun parse(lines: Sequence<String>): List<LogRecord> {
        val records = ArrayDeque<Message>()
        val byId = mutableMapOf<String, Message>()
        var current: Message? = null
        fun append(message: Message, stack: String) {
            message.record = message.record.copy(
                stack = (message.record.stack + "\n" + stack).trimStart().take(MAX_STACK)
            )
        }
        for (line in lines) {
            val m = head.matchEntire(line)
            if (m != null) {
                val identity = m.groupValues[5].split(',')
                if (identity.firstOrNull() != MODULE) { current = null; continue }
                val id = identity.getOrNull(2)
                val continuation = identity.getOrNull(3) != "0"
                val message = m.groupValues[6]
                if (continuation) {
                    // Multipart records may be separated by another thread/module's log heads.
                    current = byId[id]
                    current?.let { append(it, message) }
                    continue
                }
                val scope = message.substringBefore(": ", "LSPosed")
                val body = if (scope == "LSPosed") message else message.substringAfter(": ")
                val embedded = LogCodec.decode(body)
                val record = embedded?.copy(source = "LSPosed") ?: LogRecord(
                    m.groupValues[1].replace('T', ' '), m.groupValues[3], m.groupValues[2],
                    scope, LogCodec.event(body, m.groupValues[3] in listOf("E", "F")), body,
                    process = m.groupValues[4], source = "LSPosed"
                )
                val entry = Message(id, record)
                records.addLast(entry)
                if (id != null) byId[id] = entry
                current = entry
                while (records.size > MAX_RECORDS) {
                    val old = records.removeFirst()
                    if (byId[old.id] === old) byId.remove(old.id)
                }
            } else if (line.startsWith("[ ")) {
                current = null
            } else if (line.startsWith("----")) {
                current = null
                byId.clear()
            } else if (line.isNotBlank()) {
                current?.let { append(it, line) }
            }
        }
        return records.map { it.record }
    }
}
