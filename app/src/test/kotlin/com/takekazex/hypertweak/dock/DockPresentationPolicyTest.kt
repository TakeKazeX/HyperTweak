package com.takekazex.hypertweak.dock

import org.junit.Assert.*
import org.junit.Test

class DockPresentationPolicyTest {
    @Test fun appToHomeGestureReusesHostAndParentOwnsAnimationVisibility() {
        val home = DockPresentationPolicy.resolve(true, true, true, false, false, true)
        val inApp = DockPresentationPolicy.resolve(true, true, true, false, false, false)
        // isOnScreen includes the verified Home/recents playing-transition path even before isVisible.
        val gesture = DockPresentationPolicy.resolve(true, true, true, false, false, true)
        assertTrue(home.retainHost && inApp.retainHost && gesture.retainHost)
        assertTrue(inApp.showLayer) // The launcher parent hides it; the child must survive for the next frame.
        assertFalse(inApp.probeTexture)
        assertTrue(gesture.showLayer && gesture.probeTexture)
    }
    @Test fun lockScreenAndAssistantSuppressLayerWithoutDiscardingWarmHost() {
        listOf(
            DockPresentationPolicy.resolve(true, true, false, false, false, true),
            DockPresentationPolicy.resolve(true, true, true, true, false, true),
            DockPresentationPolicy.resolve(true, true, true, false, true, true),
        ).forEach { assertTrue(it.retainHost); assertFalse(it.showLayer); assertFalse(it.probeTexture) }
        assertFalse(DockPresentationPolicy.resolve(false, true, true, false, false, true).retainHost)
        assertFalse(DockPresentationPolicy.resolve(true, false, true, false, false, true).retainHost)
    }
}
