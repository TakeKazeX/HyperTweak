package com.takekazex.hypertweak.hook.base

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** A process-generation value. Host restoration runs before these references are released. */
class GenerationState<T>(private val initial: () -> T) : ReadWriteProperty<Any?, T> {
    @Volatile private var value = initial()
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { this.value = value }
    fun reset() { value = initial() }
}

/** Retains resource identity (including monitor locks), and drains all resets after host cleanup. */
internal class GenerationStateOwner {
    private val resets = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun <T> state(initial: () -> T): GenerationState<T> =
        GenerationState(initial).also { state -> resets += { state.reset() } }

    fun <T> resource(value: T, reset: (T) -> Unit): T = value.also {
        resets += { reset(value) }
    }

    fun reset() {
        val failures = resets.mapNotNull { runCatching(it).exceptionOrNull() }
        if (failures.isNotEmpty()) throw failures.first().apply {
            failures.drop(1).forEach(::addSuppressed)
        }
    }
}
