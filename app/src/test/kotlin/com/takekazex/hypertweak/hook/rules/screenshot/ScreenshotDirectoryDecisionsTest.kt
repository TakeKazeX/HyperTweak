package com.takekazex.hypertweak.hook.rules.screenshot

import org.junit.Assert.*
import org.junit.Test

class ScreenshotDirectoryDecisionsTest {
    @Test fun `switch change cannot split native file path and MediaStore within one save`() {
        val decisions = ScreenshotDirectoryDecisions()
        var live = true
        decisions.enter(live)
        assertTrue(decisions.current { live })
        live = false
        assertTrue("MediaStore still uses the file path's decision", decisions.current { live })
        decisions.leave()
        decisions.enter(live)
        assertFalse(decisions.current { live })
        decisions.leave()
        assertFalse(decisions.current { live })
    }

    @Test fun `nested save failure restores outer decision and releases thread state`() {
        val decisions = ScreenshotDirectoryDecisions()
        decisions.enter(false)
        try {
            decisions.enter(true)
            try { assertTrue(decisions.current { false }) } finally { decisions.leave() }
            assertFalse(decisions.current { true })
        } finally { decisions.leave() }
        assertTrue(decisions.current { true })
    }
}
