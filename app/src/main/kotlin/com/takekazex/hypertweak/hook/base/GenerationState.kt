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

    /** Installation contracts must be assigned afresh before callbacks can use a generation. */
    fun <T : Any> requiredState(): ReadWriteProperty<Any?, T> {
        val slot = state<T?> { null }
        return object : ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): T =
                slot.getValue(thisRef, property) ?: throw UninitializedPropertyAccessException(property.name)
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) =
                slot.setValue(thisRef, property, value)
        }
    }

    /** Dispatch before taking the value's monitor: never hold a lock while waiting on main. */
    fun <T : Any> resource(
        value: T,
        dispatch: (() -> Unit) -> Unit = { it() },
        reset: (T) -> Unit
    ): T = value.also {
        resets += { dispatch { synchronized(value) { reset(value) } } }
    }

    fun cleanup(action: () -> Unit) { resets += action }

    fun reset() {
        val failures = resets.mapNotNull { runCatching(it).exceptionOrNull() }
        if (failures.isNotEmpty()) throw failures.first().apply {
            failures.drop(1).forEach(::addSuppressed)
        }
    }
}
