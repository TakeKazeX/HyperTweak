package com.takekazex.hypertweak.hook.rules.systemui.icon.left

import org.junit.Assert.*
import org.junit.Test

class LeftVisibilityPolicyTest {
    private fun resolve(
        keyguard: Boolean = true, showing: Boolean = true, hide: Boolean = false,
        island: Boolean = false, shown: Boolean = true, attached: Boolean = true,
        configured: Boolean = true
    ) = LeftVisibilityPolicy.resolve(configured, attached, shown, keyguard, showing, hide, island)

    @Test fun hidingLockscreenSuppressesEveryBarSourceWithoutReleasingSlots() {
        for (keyguard in listOf(false, true)) for (island in listOf(false, true)) {
            val result = resolve(keyguard = keyguard, hide = true, island = island)
            assertFalse(result.visible)
            assertTrue(result.retain)
            assertEquals(LeftVisibilityPolicy.Result.LOCKSCREEN_HIDDEN, result)
        }
    }

    @Test fun islandExitAndNativeRevealCannotOverrideAnActiveHideRequest() {
        val events = listOf(
            resolve(hide = true, island = true, shown = false),
            resolve(hide = true, island = false, shown = false),
            resolve(hide = true, island = false, shown = true)
        )
        assertTrue(events.all { it.retain && !it.visible })
        assertTrue(resolve(hide = false).visible)
        assertFalse(resolve(hide = false, shown = false).visible) // Preserve native hidden intent.
    }

    @Test fun unlockHandsOwnershipOfPresentationBackToHomeEvenWhenHideIsStillEnabled() {
        assertFalse(resolve(keyguard = false, showing = true, hide = true).visible)
        assertFalse(resolve(keyguard = true, showing = false, hide = true).visible)
        assertTrue(resolve(keyguard = false, showing = false, hide = true).visible)
    }

    @Test fun nativeTemporaryHideRetainsClonesButDetachOrFeatureExitReleasesThem() {
        assertTrue(resolve(shown = false).retain)
        assertTrue(resolve(keyguard = false, showing = true).retain)
        assertTrue(resolve(island = true).retain)
        assertFalse(resolve(attached = false, hide = true).retain)
        assertFalse(resolve(configured = false, hide = true).retain)
    }

    @Test fun completeSurfaceMatrixNeverPresentsTwoSourcesOrAHiddenLockscreen() {
        for (showing in listOf(false, true)) for (hide in listOf(false, true))
            for (island in listOf(false, true)) for (shown in listOf(false, true)) {
                val home = resolve(keyguard = false, showing = showing, hide = hide, island = island, shown = shown)
                val keyguard = resolve(keyguard = true, showing = showing, hide = hide, island = island, shown = shown)
                assertFalse(home.visible && keyguard.visible)
                if (showing && hide) assertFalse(home.visible || keyguard.visible)
                if (shown && !island && !(showing && hide)) assertTrue(home.visible || keyguard.visible)
            }
    }

    @Test fun homeFadeDoesNotDiscardAnActiveMeasuredHandoverSource() {
        for (state in listOf(LeftVisibilityPolicy.Result.VISIBLE, LeftVisibilityPolicy.Result.HOST_HIDDEN)) {
            assertTrue(LeftVisibilityPolicy.handoverSource(state,true,false,false,false))
            assertFalse(LeftVisibilityPolicy.handoverSource(state,false,false,false,false))
            assertFalse(LeftVisibilityPolicy.handoverSource(state,true,false,true,false))
            assertFalse(LeftVisibilityPolicy.handoverSource(state,true,false,false,true))
        }
        for (state in listOf(LeftVisibilityPolicy.Result.RELEASE,LeftVisibilityPolicy.Result.OTHER_SURFACE,
            LeftVisibilityPolicy.Result.LOCKSCREEN_HIDDEN,LeftVisibilityPolicy.Result.ISLAND)) {
            assertFalse(LeftVisibilityPolicy.handoverSource(state,true,false,false,false))
        }
    }
}
