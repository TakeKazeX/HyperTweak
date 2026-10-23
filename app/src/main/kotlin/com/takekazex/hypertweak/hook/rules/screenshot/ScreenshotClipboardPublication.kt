package com.takekazex.hypertweak.hook.rules.screenshot

/** Keep pending-URI publications inside their saving thread and discard them on failed writes.
 * A callback failure must never turn an otherwise successful image save into a failed save.
 */
internal class ScreenshotClipboardPublication(private val failed: (Throwable) -> Unit) {
    private class Save(val uri: Any?) { var publication: (() -> Unit)? = null }
    private val saves = ThreadLocal<ArrayDeque<Save>>()
    fun defer(uri: Any, publish: () -> Unit): Boolean {
        val stack = saves.get() ?: return false
        val save = stack.lastOrNull { it.uri == null } ?: stack.lastOrNull { it.uri == uri } ?: return false
        save.publication = publish
        return true
    }
    fun <T> saving(uri: Any?, successful: (T) -> Boolean, write: () -> T): T {
        val stack = saves.get() ?: ArrayDeque<Save>().also(saves::set)
        val save = Save(uri)
        stack.addLast(save)
        var committed = false
        return try { write().also { committed = successful(it) } } finally {
            stack.removeLast()
            if (stack.isEmpty()) saves.remove()
            if (committed) save.publication?.let { publish ->
                try { publish() } catch (t: Throwable) { failed(t) }
            }
        }
    }
}


/** A successful write and a committed MediaStore row may arrive in either order/threads. */
internal class ScreenshotClipboardCommits(
    private val failed: (Throwable) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private class Entry(val created: Long, val ready: () -> Boolean, val publish: () -> Unit)
    private val entries = linkedMapOf<Any, Entry>()
    fun await(uri: Any, ready: () -> Boolean, publish: () -> Unit) {
        synchronized(entries) {
            val time = now()
            entries.entries.removeAll { time - it.value.created >= 30_000 }
            if (entries.size >= 16) entries.remove(entries.keys.first())
            entries[uri] = Entry(time, ready, publish)
        }
        committed(uri) // Handles commits that preceded insertion without a lost notification.
    }
    fun committed(uri: Any) {
        val entry = synchronized(entries) { entries[uri] } ?: return
        try {
            if (now() - entry.created >= 30_000) {
                synchronized(entries) { if (entries[uri] === entry) entries.remove(uri) }
                return
            }
            if (!entry.ready()) return
            val owned = synchronized(entries) { if (entries[uri] === entry) { entries.remove(uri); true } else false }
            if (owned) entry.publish()
        } catch (t: Throwable) {
            synchronized(entries) { if (entries[uri] === entry) entries.remove(uri) }
            failed(t)
        }
    }
    fun clear() { synchronized(entries) { entries.clear() } }
}
