package com.takekazex.hypertweak.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * Page-local write-through setting for keys without an [AppSettingsState] property: [read] runs
 * once, and assigning the state stores the value through [put] (for example
 * `settingsStore::putBoolean`).
 */
@Composable
internal fun <T> rememberStoredSetting(key: String, put: (String, T) -> Unit, read: () -> T): MutableState<T> =
    remember(key) { StoredSettingState(key, put, read()) }

private class StoredSettingState<T>(
    private val key: String,
    private val put: (String, T) -> Unit,
    initial: T,
) : MutableState<T> {
    private val state = mutableStateOf(initial)
    override var value: T
        get() = state.value
        set(next) {
            put(key, next)
            state.value = next
        }

    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}
