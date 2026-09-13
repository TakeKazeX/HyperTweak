package com.takekazex.hypertweak.hook.rules.systemui.icon.duo

import org.junit.Assert.*
import org.junit.Test

class DuoPrivacyGeometryTest {
    @Test fun expandingAndShrinkingFollowActualCapsuleBounds() {
        assertEquals(-64f, DuoPrivacyGeometry.offset(70f, 100f, 40f, 100f, 4f, false), 0f)
        assertEquals(-24f, DuoPrivacyGeometry.offset(70f, 100f, 80f, 100f, 4f, false), 0f)
        assertEquals(0f, DuoPrivacyGeometry.offset(70f, 100f, 106f, 110f, 4f, false), 0f)
    }
    @Test fun rtlAvoidsCapsuleInOppositeDirection() {
        assertEquals(64f, DuoPrivacyGeometry.offset(0f, 30f, 0f, 60f, 4f, true), 0f)
    }
    @Test fun dotAndHiddenStatesReleaseAvoidance() {
        assertTrue(DuoPrivacyGeometry.isTransition("START_HOME_TO_DOT"))
        assertFalse(DuoPrivacyGeometry.isTransition("COMPLETE_HOME_TO_DOT"))
        assertFalse(DuoPrivacyGeometry.isTransition("COMPLETE_HIDE_PRIVACY"))
        assertFalse(DuoPrivacyGeometry.isTransition(null))
    }
    @Test fun invalidAnimationBoundsCannotPoisonViewTranslation() {
        assertEquals(0f, DuoPrivacyGeometry.offset(0f, 30f, Float.NaN, 60f, 4f, false), 0f)
    }
}
