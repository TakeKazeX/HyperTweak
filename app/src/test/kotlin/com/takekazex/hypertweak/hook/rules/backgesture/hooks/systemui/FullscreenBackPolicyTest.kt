package com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui

import com.takekazex.hypertweak.hook.rules.backgesture.PredictiveBackPreferences
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class FullscreenBackPolicyTest {
    @Test
    fun defaultImmersiveGestureLeavesTheFirstSwipeToSystemBars() {
        val policy = policyAtDown(
            directBack = PredictiveBackPreferences.DEFAULT_FULLSCREEN_DIRECT_BACK,
        )

        assertFalse(policy.allowsBack)
        assertFalse(policy.directBack)
        assertTrue(policy.yieldToTransientBars)
    }

    @Test
    fun directModeAdmitsTheFirstSwipeAndKeepsBackWhenTransientBarsAppear() {
        val policy = policyAtDown(directBack = true)

        assertTrue(policy.allowsBack)
        assertTrue(policy.directBack)
        assertFalse(policy.yieldToTransientBars)
    }

    @Test
    fun unreadableDisplayStateCannotEnableDirectBackOrAuthenticateAHeadlessLease() {
        val policy = policyAtDown(flags = null, headless = true, directBack = true)

        assertFalse(policy.allowsBack)
        assertFalse(policy.directBack)
        assertTrue(policy.yieldToTransientBars)
    }

    @Test
    fun aospVisibilityExceptionAndHeadlessLeaseStillYieldToNewTransientBars() {
        val nativeAllowed = policyAtDown(flags = NAV_BAR_HIDDEN or IGNORE_BAR_VISIBILITY)
        val headless = policyAtDown(headless = true)

        for (policy in listOf(nativeAllowed, headless)) {
            assertTrue(policy.allowsBack)
            assertFalse(policy.directBack)
            assertTrue(policy.yieldToTransientBars)
        }
    }

    @Test
    fun visibleBarsAndAlreadyTransientBarsPreserveTheirExistingBehavior() {
        for (directBack in listOf(false, true)) {
            val visible = policyAtDown(flags = 0L, hidden = false, directBack = directBack)
            val alreadyTransient = policyAtDown(
                flags = NAV_BAR_HIDDEN or IGNORE_BAR_VISIBILITY,
                transient = true,
                directBack = directBack,
            )

            assertTrue(visible.allowsBack)
            assertFalse(visible.directBack)
            assertFalse(visible.yieldToTransientBars)
            assertTrue(alreadyTransient.allowsBack)
            assertFalse(alreadyTransient.yieldToTransientBars)
        }
    }

    @Test
    fun changingPreferencesAffectsOnlyTheNextDown() {
        var directBack = true
        val current = policyAtDown(directBack = directBack)
        directBack = false
        val next = policyAtDown(directBack = directBack)

        assertTrue(current.allowsBack)
        assertFalse(current.yieldToTransientBars)
        assertFalse(next.allowsBack)
        assertTrue(next.yieldToTransientBars)
    }

    private fun policyAtDown(
        flags: Long? = NAV_BAR_HIDDEN,
        hidden: Boolean = true,
        transient: Boolean = false,
        headless: Boolean = false,
        directBack: Boolean = false,
    ) = SystemUiInputRuntime.FullscreenBackPolicy(flags, hidden, transient, headless, directBack)

    private companion object {
        const val NAV_BAR_HIDDEN = 1L shl 1
        const val IGNORE_BAR_VISIBILITY = 1L shl 17
    }
}
