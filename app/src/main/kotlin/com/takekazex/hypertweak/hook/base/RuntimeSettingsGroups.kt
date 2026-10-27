package com.takekazex.hypertweak.hook.base

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

/** Stable group IDs and queries over the single ownership registry. */
internal object RuntimeSettingsGroups {
    // Compatibility names for callers; stable IDs and ownership are declared together.
    const val ICONS = RuntimeSettingsDefinitions.ICONS
    const val ISLANDS = RuntimeSettingsDefinitions.ISLANDS
    const val NATIVE_PHOTO_PICKER = RuntimeSettingsDefinitions.NATIVE_PHOTO_PICKER
    const val NATIVE_FILE_PICKER = RuntimeSettingsDefinitions.NATIVE_FILE_PICKER
    const val AOSP_INSTALLER = RuntimeSettingsDefinitions.AOSP_INSTALLER
    const val AOSP_SYSTEM_UI = RuntimeSettingsDefinitions.AOSP_SYSTEM_UI
    const val AOSP_SECURITY = RuntimeSettingsDefinitions.AOSP_SECURITY
    const val DARK_MODE = RuntimeSettingsDefinitions.DARK_MODE
    const val FORCE_SCREENSHOT = RuntimeSettingsDefinitions.FORCE_SCREENSHOT
    const val SCREENSHOT = RuntimeSettingsDefinitions.SCREENSHOT
    const val BACK = RuntimeSettingsDefinitions.BACK
    const val NATIVE = RuntimeSettingsDefinitions.NATIVE
    const val DOCK = RuntimeSettingsDefinitions.DOCK
    const val VOLUME = RuntimeSettingsDefinitions.VOLUME
    const val XMSF = RuntimeSettingsDefinitions.XMSF
    const val GESTURE_BAR = RuntimeSettingsDefinitions.GESTURE_BAR
    const val GESTURE_BAR_UI = RuntimeSettingsDefinitions.GESTURE_BAR_UI
    const val GATED_FEATURES = RuntimeSettingsDefinitions.GATED_FEATURES
    const val APP_DETAILS = RuntimeSettingsDefinitions.APP_DETAILS
    const val SECURITY_POPUPS = RuntimeSettingsDefinitions.SECURITY_POPUPS
    const val SECURITY_DOCK = RuntimeSettingsDefinitions.SECURITY_DOCK
    const val SECURITY_CHECKS = RuntimeSettingsDefinitions.SECURITY_CHECKS
    const val SECURITY_PRIVACY = RuntimeSettingsDefinitions.SECURITY_PRIVACY
    const val SECURITY_WARNINGS = RuntimeSettingsDefinitions.SECURITY_WARNINGS
    const val SECURITY_BUBBLES = RuntimeSettingsDefinitions.SECURITY_BUBBLES
    const val SECURITY_CLIPBOARD = RuntimeSettingsDefinitions.SECURITY_CLIPBOARD
    const val OFFLINE_CONFIG = RuntimeSettingsDefinitions.OFFLINE_CONFIG
    const val XIAOAI_GESTURES = RuntimeSettingsDefinitions.XIAOAI_GESTURES
    const val SETTINGS_AI = RuntimeSettingsDefinitions.SETTINGS_AI
    const val SHARE_PANEL = RuntimeSettingsDefinitions.SHARE_PANEL
    const val AOD = RuntimeSettingsDefinitions.AOD
    const val KEYGUARD = RuntimeSettingsDefinitions.KEYGUARD
    const val HEADER = RuntimeSettingsDefinitions.HEADER
    const val NOTIFICATION_TEXT = RuntimeSettingsDefinitions.NOTIFICATION_TEXT
    const val MEDIA_CARD = RuntimeSettingsDefinitions.MEDIA_CARD
    const val CONTROL_CENTER_PLUGIN = RuntimeSettingsDefinitions.CONTROL_CENTER_PLUGIN
    const val GLASS = RuntimeSettingsDefinitions.GLASS
    const val POWER_SAVE = RuntimeSettingsDefinitions.POWER_SAVE
    const val LOCKSCREEN = RuntimeSettingsDefinitions.LOCKSCREEN
    const val AOD_CAPABILITY = RuntimeSettingsDefinitions.AOD_CAPABILITY
    const val NOTIFICATION_ICONS = RuntimeSettingsDefinitions.NOTIFICATION_ICONS
    const val NOTIFICATION_MINI_WINDOW = RuntimeSettingsDefinitions.NOTIFICATION_MINI_WINDOW
    const val NOTIFICATION_WHITELIST = RuntimeSettingsDefinitions.NOTIFICATION_WHITELIST
    const val FREEFORM_BLUR = RuntimeSettingsDefinitions.FREEFORM_BLUR
    const val GOOGLE_APP = RuntimeSettingsDefinitions.GOOGLE_APP
    const val GOOGLE_SETTINGS = RuntimeSettingsDefinitions.GOOGLE_SETTINGS
    const val GMS_RESTRICTIONS = RuntimeSettingsDefinitions.GMS_RESTRICTIONS
    const val QUICK_SHARE = RuntimeSettingsDefinitions.QUICK_SHARE
    const val PASSKEY = RuntimeSettingsDefinitions.PASSKEY
    const val FCM = RuntimeSettingsDefinitions.FCM
    const val REVISION = RuntimeSettingsDefinitions.REVISION
    private val groups get() = RuntimeSettingsDefinitions.groups
    val backKeys get() = RuntimeSettingsDefinitions.backKeys
    val strictBooleanKeys get() = RuntimeSettingsDefinitions.strictBooleanKeys
    val targets: Set<String> get() = groups.values.flatMap { it.targets }.toSet()
    fun target(process: String, systemServer: Boolean = false): String = if (systemServer || process == "system_server") "android" else {
        val base = process.substringBefore(':')
        when {
            // Every Security Center subprocess hosts part of the same feature set; before this,
            // `.power` was not normalized and therefore never received a settings snapshot at all
            // (its PowerSaveOverrideHooker and PowerRankingHooker only ever saw boot values).
            base in setOf("com.miui.securitycenter", "com.miui.securitycenter.remote",
                "com.miui.securitycenter.cache", "com.miui.securitycenter.power",
                "com.miui.securitycenter.bootaware", "com.miui.securitycenter.uniauth") -> "com.miui.securitycenter"
            base in setOf("com.google.android.gms.persistent", "com.google.android.gms.unstable",
                "com.google.android.gms.ui", "com.google.android.gms.learning", "com.google.android.gms.feedback",
                "com.google.android.gms.remapping1", "com.google.android.gms.room") -> "com.google.android.gms"
            else -> base
        }
    }
    fun forTarget(target: String): Set<String> = groups.filterValues { target in it.targets }.keys
    fun forProcess(process: String, systemServer: Boolean = false): Set<String> = forTarget(target(process, systemServer))
    fun owners(key: String): Set<String> = groups.filterValues { it.owns(key) }.keys
    fun owns(key: String): Boolean = owners(key).isNotEmpty()
    fun owns(target: String, key: String): Boolean = groups.values.any { target in it.targets && it.owns(key) }
    fun affected(key: String?): Set<String> = if (key == null || key == "prefs_epoch") groups.keys
        else groups.filterValues { it.owns(key) }.keys
    fun changed(before: Map<String, Any>, after: Map<String, Any>): Set<String> =
        (before.keys + after.keys).filter {
            // Ordering/commit identities are carried by the snapshot, not UI invalidations.
            it != "prefs_epoch" && it != PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION && before[it] != after[it]
        }.flatMap(::affected).toSet()
    fun subset(target: String, values: Map<String, Any>): Map<String, Any> = values.filterKeys { owns(target, it) }
}
