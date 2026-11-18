package com.takekazex.hypertweak.ui.state

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/** One authoritative read per setting, reused after service rebind/reset instead of a second list. */
internal class ReloadableSettings {
    private val readers = ArrayList<() -> Unit>()

    /** Read-only mirror: assignments only change UI state. */
    fun <T> preference(read: () -> T): MutableState<T> = mutableStateOf(read()).also { state ->
        readers += { state.value = read() }
    }

    /** Write-through setting: assigning persists through [write]; [reload] refreshes without writing. */
    fun <T> preference(write: (T) -> Unit, read: () -> T): MutableState<T> =
        PersistedState(read(), write).also { state -> readers += { state.load(read()) } }

    fun reload() { readers.forEach { it() } }
}

private class PersistedState<T>(initial: T, private val write: (T) -> Unit) : MutableState<T> {
    private val state = mutableStateOf(initial)
    override var value: T
        get() = state.value
        set(next) {
            write(next)
            state.value = next
        }

    fun load(next: T) { state.value = next }
    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}
