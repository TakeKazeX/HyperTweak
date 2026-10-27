package com.takekazex.hypertweak.hook.rules.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeSwitcherPolicyTest {
    @Test fun selectedCallerCanListAnotherEnabledKeyboardEvenWhenTargetIsNotSelected() {
        assertTrue(ImeSwitcherPolicy.mayAccess("wetype", setOf("sogou", "wetype"),
            setOf("sogou"), setOf("sogou")))
    }

    @Test fun unrelatedUidAndDisabledTargetKeepPlatformVisibilityRules() {
        assertFalse(ImeSwitcherPolicy.mayAccess("wetype", setOf("sogou", "wetype"),
            setOf("another.app"), setOf("sogou")))
        assertFalse(ImeSwitcherPolicy.mayAccess("wetype", setOf("sogou"),
            setOf("sogou"), setOf("sogou")))
        assertFalse(ImeSwitcherPolicy.mayAccess("wetype", setOf("wetype"),
            emptySet(), setOf("sogou")))
    }
}
