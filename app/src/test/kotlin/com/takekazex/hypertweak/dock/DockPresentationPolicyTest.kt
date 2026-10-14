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
    @Test fun screenOffAndKeyguardKeepChildForParentTransitionButSuspendTextureProbes() {
        listOf(
            DockPresentationPolicy.resolve(true, true, false, false, false, true),
            DockPresentationPolicy.resolve(true, true, true, true, false, true),
            DockPresentationPolicy.resolve(true, true, false, true, false, false),
        ).forEach { assertTrue(it.retainHost); assertTrue(it.showLayer); assertFalse(it.probeTexture) }
        val unlocked = DockPresentationPolicy.resolve(true, true, true, false, false, true)
        assertTrue(unlocked.showLayer && unlocked.probeTexture)
    }
    @Test fun nativeHotseatHideAndDisabledOrInvalidHostStillSuppressLayer() {
        val hidden = DockPresentationPolicy.resolve(true, true, true, false, true, true)
        assertTrue(hidden.retainHost)
        assertFalse(hidden.showLayer || hidden.probeTexture)
        assertFalse(DockPresentationPolicy.resolve(false, true, true, false, false, true).retainHost)
        assertFalse(DockPresentationPolicy.resolve(true, false, true, false, false, true).retainHost)
    }
}
