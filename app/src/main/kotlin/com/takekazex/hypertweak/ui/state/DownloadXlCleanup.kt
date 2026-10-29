package com.takekazex.hypertweak.ui.state

import androidx.compose.runtime.staticCompositionLocalOf
import com.takekazex.hypertweak.util.DownloadXlStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal val LocalDownloadXlCleanup = staticCompositionLocalOf<DownloadXlCleanup> {
    error("DownloadXlCleanup was not provided by the app shell")
}

/** App-shell lifetime: navigating away does not abandon a check or confirmed deletion. */
internal class DownloadXlCleanup(
    private val scope: CoroutineScope,
    private val exists: suspend () -> Boolean? = DownloadXlStorage::directoryExists,
    private val delete: suspend () -> Boolean? = DownloadXlStorage::deleteDirectory,
) {
    data class State(val busy: Boolean = false, val confirmDelete: Boolean = false)
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var generation = 0

    fun dismiss() { mutableState.value = mutableState.value.copy(confirmDelete = false) }

    fun disable(commit: (Boolean) -> Unit) {
        generation++
        dismiss()
        commit(false)
    }

    fun checkAndEnable(commit: (Boolean) -> Unit, failed: () -> Unit) {
        if (state.value.busy || state.value.confirmDelete) return
        val token = ++generation
        mutableState.value = State(busy = true)
        scope.launch {
            try {
                val present = exists()
                if (token != generation) return@launch
                if (present == null) failed()
                else {
                    commit(true)
                    mutableState.value = State(busy = true, confirmDelete = present)
                }
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    fun confirm(deleted: (Boolean) -> Unit) {
        if (state.value.busy || !state.value.confirmDelete) return
        mutableState.value = State(busy = true)
        scope.launch {
            try { deleted(delete() == true) }
            finally { mutableState.value = State() }
        }
    }
}
