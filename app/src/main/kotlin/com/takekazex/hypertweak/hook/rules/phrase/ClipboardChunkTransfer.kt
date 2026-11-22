package com.takekazex.hypertweak.hook.rules.phrase

import android.content.ContentProvider
import android.content.ContentValues
import android.os.SystemClock
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/** The host has one chunk buffer per provider. Keep transfers from different IMEs apart. */
internal class ClipboardChunkTransfer(private val chunks: Field, private val received: Field) {
    private data class Transfer(val uid: Int, val total: Int, val expires: Long)
    private val transfers = Collections.synchronizedMap(WeakHashMap<ContentProvider, Transfer>())

    // Caller serializes this with the actual host insert for the same history URI.
    fun admit(provider: ContentProvider, uid: Int, values: ContentValues?): Boolean {
        val index = values?.getAsInteger("chunk_index") ?: return false
        val total = values.getAsInteger("total_size") ?: return false
        if (index < 0 || total <= 0 || values.getAsString("json_chunk").isNullOrEmpty()) return false
        val buffer = buffer(provider)
        if (index == 0) {
            buffer.clear()
            received.setInt(provider, 0)
            transfers[provider] = Transfer(uid, total, SystemClock.elapsedRealtime() + 15_000L)
        }
        val transfer = transfers[provider] ?: return false
        return transfer.uid == uid && transfer.total == total && index == buffer.size &&
            SystemClock.elapsedRealtime() < transfer.expires
    }

    fun finish(provider: ContentProvider) {
        if (buffer(provider).isEmpty()) transfers.remove(provider)
    }

    fun hasPending(provider: ContentProvider): Boolean {
        val transfer = transfers[provider] ?: return false
        if (SystemClock.elapsedRealtime() < transfer.expires) return true
        retire(provider)
        return false
    }

    fun retireAll() {
        val providers = synchronized(transfers) { transfers.keys.toList() }
        providers.forEach { provider -> synchronized(provider) { retire(provider) } }
    }

    fun retire(provider: ContentProvider) {
        if (transfers.remove(provider) == null) return
        buffer(provider).clear()
        received.setInt(provider, 0)
    }

    private fun buffer(provider: ContentProvider): MutableMap<*, *> = chunks.get(provider) as MutableMap<*, *>
}
