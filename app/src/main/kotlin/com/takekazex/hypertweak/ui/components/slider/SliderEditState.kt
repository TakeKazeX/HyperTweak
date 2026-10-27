package com.takekazex.hypertweak.ui.components.slider

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Local drag preview. Only [finish] produces a changed, normalized value to persist. */
@Stable
internal class SliderEditState(initial: Float) {
    var value by mutableFloatStateOf(initial)
        private set
    private var source = initial
    private var committed = initial
    private var editing = false

    fun update(value: Float) {
        this.value = value
        editing = true
    }

    fun sync(value: Float) {
        if (value == source) return
        source = value
        if (!editing) {
            this.value = value
            committed = value
        }
    }

    fun finish(normalize: (Float) -> Float = { it }): Float? {
        val result = normalize(value)
        value = result
        editing = false
        if (result == committed) return null
        committed = result
        return result
    }
}

@Composable
internal fun rememberSliderEdit(value: Float): SliderEditState {
    val state = remember { SliderEditState(value) }
    SideEffect { state.sync(value) }
    return state
}
