package com.takekazex.hypertweak.hook.rules.screenshot

/** Serializes native mutable Rect/Paint access and retirement with in-flight renders. */
internal class ScreenshotFrameMaterialCache<T : Any>(private val dispose: (T) -> Unit) {
    private var key: Any? = null
    private var value: T? = null
    private var closed = false

    @Synchronized
    fun <R> use(key: Any, load: () -> T?, block: (T) -> R): R? {
        if (closed) return null
        if (this.key != key) {
            val old = value
            value = null
            this.key = null
            old?.let(dispose)
        }
        val current = value ?: load()?.also {
            value = it
            this.key = key
        } ?: return null
        return block(current)
    }

    @Synchronized
    fun close() {
        closed = true
        val old = value
        value = null
        key = null
        old?.let(dispose)
    }
}
