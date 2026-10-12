package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui

import org.junit.Assert.*
import org.junit.Test

class RecentsIdleRetryTest {
    private val session = Any()
    private val input = Any()
    private val focus = "42:example/.Activity"
    private fun pending() = RecentsIdleRetry(session, input, 7, focus)

    @Test fun `native idle permits one upgrade of the original physical stream`() {
        val retry = pending()
        assertTrue(retry.claim(session, input, 7, focus, true, false, true))
        assertFalse(retry.claim(session, input, 7, focus, true, false, true))
    }

    @Test fun `release before native idle retires the retry and preserves ordinary fallback`() {
        val retry = pending()
        assertFalse(retry.claim(session, input, 7, focus, false, true, true))
        assertFalse(retry.claim(session, input, 7, focus, true, false, true))
    }

    @Test fun `a different activity or task cannot inherit a delayed back`() {
        for (other in listOf("43:example/.Activity", "42:example/.AnotherActivity", null)) {
            assertFalse(pending().claim(session, input, 7, other, true, false, true))
        }
    }

    @Test fun `replacement input owners and newer gestures reject old idle callbacks`() {
        assertFalse(pending().claim(Any(), input, 7, focus, true, false, true))
        assertFalse(pending().claim(session, Any(), 7, focus, true, false, true))
        assertFalse(pending().claim(session, input, 8, focus, true, false, true))
    }

    @Test fun `a new native Recents transition cannot be bypassed by an earlier idle callback`() {
        val retry = pending()
        assertFalse(retry.claim(session, input, 7, focus, true, false, false))
        assertFalse(retry.claim(session, input, 7, focus, true, false, true))
    }
}
