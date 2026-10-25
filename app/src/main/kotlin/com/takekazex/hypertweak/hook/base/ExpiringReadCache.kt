package com.takekazex.hypertweak.hook.base

import java.util.concurrent.ConcurrentHashMap

/** Short-lived read memo. Expiry never removes a concurrent replacement of the same key. */
internal class ExpiringReadCache(private val ttlNanos: Long, private val clock: () -> Long = System::nanoTime) {
    private class Entry(val value: Any?, val expiresAtNanos: Long)
    private val entries = ConcurrentHashMap<String, Entry>()
    operator fun get(key: String): Any? {
        val entry = entries[key] ?: return null
        if (entry.expiresAtNanos <= clock()) {
            entries.remove(key, entry)
            return null
        }
        return entry.value
    }
    operator fun set(key: String, value: Any?) { entries[key] = Entry(value, clock() + ttlNanos) }
    fun invalidate(key: String) { entries.remove(key) }
    fun clear() = entries.clear()
}
