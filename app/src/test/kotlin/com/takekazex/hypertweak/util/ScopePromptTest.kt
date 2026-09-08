package com.takekazex.hypertweak.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScopePromptTest {
    @Test
    fun `prompt ids round trip action and package`() {
        val prompt = ScopePrompt(ScopePromptAction.RESTORE, "com.example.keyboard")

        assertEquals("restore:com.example.keyboard", prompt.id)
        assertEquals(prompt, ScopePrompt.parse(prompt.id))
    }

    @Test
    fun `invalid prompt ids are ignored`() {
        assertNull(ScopePrompt.parse("unknown:com.example.app"))
        assertNull(ScopePrompt.parse("restore:"))
        assertNull(ScopePrompt.parse("remove"))
    }
}
