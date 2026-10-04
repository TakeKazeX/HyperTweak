package com.takekazex.hypertweak.hook.rules.systemui.icon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiActivityPolicyTest {
    @Test
    fun removingNativeStandardAndMeteredBadgeRestoresTrailingActivity() {
        assertTrue(WifiActivityPolicy.nativeInoutLeft(false, 6))
        assertTrue(WifiActivityPolicy.nativeInoutLeft(true, 0))
        assertFalse(WifiActivityPolicy.nativeInoutLeft(false, 0))
        assertFalse(WifiActivityPolicy.nativeInoutLeft(false, -1))
    }
}
