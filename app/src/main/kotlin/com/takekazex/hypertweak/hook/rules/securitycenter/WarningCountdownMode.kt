package com.takekazex.hypertweak.hook.rules.securitycenter

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

/** A preinstalled direct-confirm callback must remain inert in both other modes. */
internal fun warningShouldDirectApprove(mode: Int): Boolean =
    mode == PlatformSettingsKeys.SECURITY_CENTER_WARNING_DIRECT_APPROVE
