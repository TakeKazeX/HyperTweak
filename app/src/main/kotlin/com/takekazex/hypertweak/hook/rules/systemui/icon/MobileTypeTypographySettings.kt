package com.takekazex.hypertweak.hook.rules.systemui.icon

import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.runtime.settings.hookSetting
import com.takekazex.hypertweak.settings.contract.IconSettingsKeys
import com.takekazex.hypertweak.settings.contract.MobileTypeTypography

internal object MobileTypeTypographySettings {
    private val value by hookSetting(MobileTypeTypography()) {
        MobileTypeTypography(
            Preferences.getBoolean(IconSettingsKeys.KEY_ICON_TYPE_TYPOGRAPHY, false),
            Preferences.getFloat(IconSettingsKeys.KEY_ICON_TYPE_SIZE_PERCENT, 100f),
            Preferences.getInt(IconSettingsKeys.KEY_ICON_TYPE_WEIGHT, 400)
        ).safe()
    }
    val current get() = value
}
