package com.takekazex.hypertweak.hook.base

import java.lang.ref.WeakReference

/** Restores only the membership changed by this owner; unrelated native entries stay intact. */
internal class CollectionMembershipOverride<T>(private val value: T) {
    private class Entry<T>(val collection: WeakReference<MutableCollection<T>>, val present: Boolean)
    private val entries = mutableListOf<Entry<T>>()

    @Synchronized fun apply(collection: MutableCollection<T>?, present: Boolean) {
        if (collection == null) return
        entries.removeAll { it.collection.get() == null }
        val wasPresent = value in collection
        if (wasPresent == present) return
        val entry = entries.firstOrNull { it.collection.get() === collection }
        if (present) collection.add(value) else collection.remove(value)
        if (entry == null) entries += Entry(WeakReference(collection), wasPresent)
    }

    @Synchronized fun restore() {
        val work = entries.toList()
        var failure: Throwable? = null
        work.forEach { entry ->
            val collection = entry.collection.get() ?: return@forEach
            try {
                if ((value in collection) != entry.present) {
                    if (entry.present) collection.add(value) else collection.remove(value)
                }
                entries.remove(entry)
            } catch (error: Throwable) { failure = failure?.also { it.addSuppressed(error) } ?: error }
        }
        failure?.let { throw it }
    }
}
