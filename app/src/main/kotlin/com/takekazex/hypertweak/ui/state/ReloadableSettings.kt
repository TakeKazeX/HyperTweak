package com.takekazex.hypertweak.ui.state

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/** One authoritative read per setting, reused after service rebind/reset instead of a second list. */
internal class ReloadableSettings {
    private val readers = ArrayList<() -> Unit>()
    fun <T> preference(read: () -> T): MutableState<T> = mutableStateOf(read()).also { state ->
        readers += { state.value = read() }
    }
    fun reload() { readers.forEach { it() } }
}
