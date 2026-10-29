package com.takekazex.hypertweak.ui.state

import com.takekazex.hypertweak.settings.contract.DesktopGridStatus
import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Explicit commits survive page disposal; drafts never enter this owner. */
internal object DesktopGridWriter {
    data class State(val saving: Boolean = false, val requested: Int = 0, val status: DesktopGridStatus? = null,
        val saveFailed: Boolean = false, val queried: Boolean = false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var queryJob: Job? = null
    private var rollback: (() -> Unit)? = null
    @Synchronized fun submit(code: Int, commit: (Int) -> Boolean, onFailure: () -> Unit, query: suspend () -> DesktopGridStatus?) {
        require(LauncherDesktopKeys.validGrid(code))
        if (mutable.value.saving) return
        generation++
        rollback = onFailure
        mutable.value = State(saving = true, requested = code)
        scope.launch {
            val saved = runCatching { commit(code) }.getOrDefault(false)
            mutable.value = State(requested = code, saveFailed = !saved)
            if (saved) refresh(query)
        }
    }
    @Synchronized fun refresh(query: suspend () -> DesktopGridStatus?) {
        queryJob?.cancel()
        val ticket = generation
        queryJob = scope.launch {
            repeat(4) {
                val status = query()
                val current = synchronized(this@DesktopGridWriter) {
                    if (generation != ticket || mutable.value.saving) false
                    else { mutable.value = mutable.value.copy(status = status, queried = true); true }
                }
                if (!current) return@launch
                if (status?.stage == 7 && status.requested == mutable.value.requested) {
                    val undo = synchronized(this@DesktopGridWriter) { rollback.also { rollback = null } }
                    if (undo != null) runCatching { undo() }
                    return@launch
                }
                if (status?.applied == true || status?.restored == true) return@launch
                delay(300)
            }
        }
    }
}
