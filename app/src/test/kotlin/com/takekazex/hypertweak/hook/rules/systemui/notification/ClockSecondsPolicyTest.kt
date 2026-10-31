package com.takekazex.hypertweak.hook.rules.systemui.notification

import org.junit.Assert.*
import org.junit.Test

class ClockSecondsPolicyTest {
    @Test fun `temporary seconds apply to both baselines and expire together`() {
        val policy = ClockSecondsPolicy(300_000)
        policy.toggle(false, 100)
        assertTrue(policy.enabled(false, 101))
        assertTrue(policy.enabled(true, 101))
        assertTrue(policy.enabled(false, 300_099))
        assertFalse(policy.enabled(false, 300_100))
        assertTrue(policy.enabled(true, 300_100))
    }

    @Test fun `press on other surface cancels override and restores independent switches`() {
        val policy = ClockSecondsPolicy(300_000)
        policy.toggle(false, 100)
        policy.toggle(true, 200)
        assertFalse(policy.isActive(200))
        assertFalse(policy.enabled(false, 200))
        assertTrue(policy.enabled(true, 200))
    }

    @Test fun `persistent seconds can still be hidden temporarily on both surfaces`() {
        val policy = ClockSecondsPolicy(300_000)
        policy.toggle(true, 100)
        assertFalse(policy.enabled(true, 101))
        assertFalse(policy.enabled(false, 101))
        policy.clear()
        assertTrue(policy.enabled(true, 102))
        assertFalse(policy.enabled(false, 102))
    }

    @Test fun `switch changes do not cancel temporary mode and become baseline at expiry`() {
        val policy = ClockSecondsPolicy(300_000)
        policy.toggle(false, 0)
        assertTrue(policy.enabled(false, 10))
        assertTrue(policy.enabled(true, 10))
        assertFalse(policy.enabled(false, 300_000))
        assertTrue(policy.enabled(true, 300_000))
        policy.toggle(false, 300_001)
        assertTrue(policy.enabled(false, 300_002))
    }
}
