package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import org.junit.Assert.*
import org.junit.Test

class DuoHandoverTest {
    @Test fun briefMissingRouteKeepsPictureButPersistentLossExpires() {
        assertTrue(DuoHandover.keepPrevious(1000, 1200))
        assertFalse(DuoHandover.keepPrevious(1000, 1350))
        assertFalse(DuoHandover.keepPrevious(-1, 100))
    }
}
