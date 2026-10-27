package com.takekazex.hypertweak.ui.components.slider

import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class SliderEditStateTest {
    @Test fun draggingOnlyPublishesFinalValueOnce() {
        val state = SliderEditState(10f)
        val writes = mutableListOf<Float>()
        for (value in 11..80) state.update(value.toFloat())
        assertEquals(80f, state.value, 0f)
        assertTrue(writes.isEmpty())
        state.finish()?.let(writes::add)
        state.finish()?.let(writes::add)
        assertEquals(listOf(80f), writes)
    }

    @Test fun returningToOriginalValueNeedsNoWrite() {
        val state = SliderEditState(10f)
        state.update(30f)
        state.update(10f)
        assertNull(state.finish())
    }

    @Test fun integerNormalizationPrecedesEqualityAndUpdatesThumb() {
        val state = SliderEditState(10f)
        state.update(10.3f)
        assertNull(state.finish { it.roundToInt().toFloat() })
        assertEquals(10f, state.value, 0f)
        state.update(10.7f)
        assertEquals(11f, state.finish { it.roundToInt().toFloat() })
    }

    @Test fun externalResetRefreshesIdleEditor() {
        val state = SliderEditState(10f)
        state.sync(0f)
        assertEquals(0f, state.value, 0f)
        assertNull(state.finish())
    }

    @Test fun previewEchoDoesNotBecomeCommittedValue() {
        val state = SliderEditState(1f)
        state.update(1.5f)
        state.sync(1.5f) // Glass preview recomposes its parent during dragging.
        assertEquals(1.5f, state.finish())
        assertNull(state.finish())
    }

    @Test fun staleSourceDoesNotUndoReleaseWhileWriterAcknowledges() {
        val state = SliderEditState(10f)
        state.update(20f)
        assertEquals(20f, state.finish())
        state.sync(10f)
        assertEquals(20f, state.value, 0f)
        assertNull(state.finish())
        state.sync(20f)
        state.sync(0f)
        assertEquals(0f, state.value, 0f)
    }

    @Test fun continuousFloatIsNotQuantizedToDisplayPercentage() {
        val state = SliderEditState(0f)
        state.update(0.12345f)
        assertEquals(0.12345f, state.finish())
    }
}
