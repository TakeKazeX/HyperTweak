package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.MobileTypeTypography

internal object MobileTypeTypographySettings {
    private val value by hookSetting(MobileTypeTypography()) {
        MobileTypeTypography(
            Preferences.iconConfig().boolean(IconSettingsKeys.KEY_ICON_TYPE_TYPOGRAPHY),
            Preferences.iconConfig().float(IconSettingsKeys.KEY_ICON_TYPE_SIZE_PERCENT),
            Preferences.iconConfig().int(IconSettingsKeys.KEY_ICON_TYPE_WEIGHT)
        ).safe()
    }
    val current get() = value
}
