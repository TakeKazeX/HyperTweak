package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys as Keys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningCountdownModeTest {
    @Test fun directConfirmationIsInertAfterSwitchingModesOrResetting() {
        assertFalse(warningShouldDirectApprove(Keys.SECURITY_CENTER_WARNING_FOLLOW_SYSTEM))
        assertFalse(warningShouldDirectApprove(Keys.SECURITY_CENTER_WARNING_SKIP_COUNTDOWN))
        assertTrue(warningShouldDirectApprove(Keys.SECURITY_CENTER_WARNING_DIRECT_APPROVE))
        assertFalse(warningShouldDirectApprove(Keys.SECURITY_CENTER_WARNING_SKIP_COUNTDOWN))
        assertFalse(warningShouldDirectApprove(Keys.SECURITY_CENTER_WARNING_FOLLOW_SYSTEM))
    }
}
