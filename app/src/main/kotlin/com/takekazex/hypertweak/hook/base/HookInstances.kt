package com.takekazex.hypertweak.hook.base

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** Passive startup/replacement recovery. No host instance or custom equality is retained. */
internal class HookInstances {
    private val queue = ReferenceQueue<Any>()
    private class Entry(value: Any, queue: ReferenceQueue<Any>) : WeakReference<Any>(value, queue) {
        private val identity = System.identityHashCode(value)
        override fun hashCode() = identity
        override fun equals(other: Any?): Boolean = this === other ||
            (other is Entry && get()?.let { it === other.get() } == true)
    }
    private val entries = HashSet<Entry>()
    private fun drain() { while (true) entries.remove(queue.poll() ?: return) }
    @Synchronized fun add(value: Any?) { drain(); if (value != null) entries.add(Entry(value, queue)) }
    @Synchronized fun snapshot(): List<Any> { drain(); return entries.mapNotNull { it.get() } }
    @Synchronized fun clear() { entries.clear(); drain() }
}
