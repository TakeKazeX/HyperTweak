package com.takekazex.hypertweak.hook.rules.systemui.icon

internal object IconSettingsPreferencePolicy {
    fun affectsIcons(key: String?): Boolean = key == null || key == "prefs_epoch" ||
        key.startsWith("icon_") || key.startsWith("stacked_s_") ||
        key.startsWith("systemui_cc_") || key.startsWith("statusbar_hide_carrier_") ||
        key.startsWith("systemui_ls_hide_carrier_") || key == "statusbar_region_sampling" ||
        key == "statusbar_notif_max" || key == "statusbar_notif_icon_max" ||
        key == "statusbar_ignore_sys_hide"
}
