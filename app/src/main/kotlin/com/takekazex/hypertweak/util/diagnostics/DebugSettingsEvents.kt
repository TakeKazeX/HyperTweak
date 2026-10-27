package com.takekazex.hypertweak.util.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object DebugSettingsEvents {
    private val mutable = MutableStateFlow(0L)
    val revision = mutable.asStateFlow()
    @Synchronized fun refresh() { mutable.value += 1 }
}
