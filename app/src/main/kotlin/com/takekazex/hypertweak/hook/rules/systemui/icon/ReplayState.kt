package com.takekazex.hypertweak.hook.rules.systemui.icon

import java.util.concurrent.CopyOnWriteArrayList

/** A generation owns the returned closeable. Deferred replay checks that it still owns it. */
internal class ReplayState<T>(initial: T, private val dispatch: (() -> Unit) -> Unit, private val notify: (() -> Unit) -> Unit) {
    private class Listener(val callback: () -> Unit)
    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile var value: T = initial
        private set
    fun publish(next: T) {
        if (next == value) return
        value = next
        listeners.forEach { notify(it.callback) }
    }
    fun subscribe(callback: () -> Unit): AutoCloseable {
        val listener = Listener(callback)
        listeners += listener
        dispatch { if (listener in listeners) notify(callback) }
        return AutoCloseable { listeners.remove(listener) }
    }
}
