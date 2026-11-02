package com.takekazex.hypertweak.hook.rules.systemui.lockscreen

import org.junit.Assert.*
import org.junit.Test

class KeyguardSurfacePolicyTest {
    @Test fun lockingWaitsForEitherModelAndUnlockWaitsForBoth() {
        assertEquals(false, KeyguardSurfacePolicy.showing(0, false, false))
        // Either callback may arrive first, including temporary keyguard occlusion.
        assertEquals(true, KeyguardSurfacePolicy.showing(0, true, false))
        assertEquals(true, KeyguardSurfacePolicy.showing(1, false, false))
        assertEquals(true, KeyguardSurfacePolicy.showing(1, true, true))
        assertEquals(true, KeyguardSurfacePolicy.showing(2, true, false))
        assertEquals(true, KeyguardSurfacePolicy.showing(0, true, false))
        assertEquals(true, KeyguardSurfacePolicy.showing(1, false, false))
        assertEquals(false, KeyguardSurfacePolicy.showing(0, false, false))
    }

    @Test fun occludedAppInShadeCanShowItsOrdinaryStatusBar() {
        assertEquals(false, KeyguardSurfacePolicy.showing(0, true, true))
    }

    @Test fun missingModelsPreserveFallbackAndInvalidStatesAreUnknown() {
        assertNull(KeyguardSurfacePolicy.showing(null, null, null))
        assertNull(KeyguardSurfacePolicy.showing(3, null, null))
        assertNull(KeyguardSurfacePolicy.showing(null, true, null))
        assertEquals(true, KeyguardSurfacePolicy.showing(1, null, null))
        assertEquals(false, KeyguardSurfacePolicy.showing(0, null, null))
        assertEquals(true, KeyguardSurfacePolicy.showing(null, true, false))
        assertEquals(false, KeyguardSurfacePolicy.showing(null, true, true))
    }
}
