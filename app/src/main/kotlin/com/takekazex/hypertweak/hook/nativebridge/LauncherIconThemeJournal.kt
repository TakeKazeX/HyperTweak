package com.takekazex.hypertweak.hook.nativebridge

/** Recovery journal, not a second settings authority. Null originals preserve absent settings. */
internal class LauncherIconThemeJournal(private val port: Port) {
    data class Record(val before: String?, val last: String?)
    interface Port {
        fun readHost(key: String): String?
        fun writeHost(key: String, value: String?): Boolean
        fun load(key: String): Record?
        fun save(key: String, record: Record?): Boolean
    }
    /** Prior native value while we still own the current value; preserves newer native edits. */
    fun original(key: String): String? {
        val current = port.readHost(key)
        val record = port.load(key)
        return if (record != null && current == record.last) record.before else current
    }
    fun write(key: String, value: String) {
        val current = port.readHost(key)
        val saved = port.load(key)
        val record = if (saved == null || current != saved.last) Record(current, current).also {
            check(port.save(key, it))
        } else saved
        if (current == value) return
        check(port.save(key, record.copy(last = value)))
        check(port.writeHost(key, value))
    }
    fun restore(key: String) {
        val record = port.load(key) ?: return
        if (port.readHost(key) == record.last) check(port.writeHost(key, record.before))
        check(port.save(key, null))
    }
}
