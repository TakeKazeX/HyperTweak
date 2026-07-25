package com.takekazex.hypertweak.hook.rules.systemui

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureBarActionTest {
    @Test
    fun persistedIdsRemainStable() {
        assertEquals(GestureBarAction.DISABLED, GestureBarAction.fromPersistedId(0))
        assertEquals(GestureBarAction.DEFAULT_ASSISTANT, GestureBarAction.fromPersistedId(1))
        assertEquals(GestureBarAction.CIRCLE_TO_SEARCH, GestureBarAction.fromPersistedId(2))
        assertEquals(GestureBarAction.GEMINI, GestureBarAction.fromPersistedId(3))
        assertEquals(GestureBarAction.CHATGPT, GestureBarAction.fromPersistedId(4))
    }

    @Test
    fun unknownPersistedIdIsDisabled() {
        assertEquals(GestureBarAction.DISABLED, GestureBarAction.fromPersistedId(-1))
        assertEquals(GestureBarAction.DISABLED, GestureBarAction.fromPersistedId(99))
    }
}
