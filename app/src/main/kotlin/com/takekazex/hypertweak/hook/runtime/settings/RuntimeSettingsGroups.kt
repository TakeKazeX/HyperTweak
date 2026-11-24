package com.takekazex.hypertweak.hook.runtime.settings

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

/** Stable group IDs and queries over the single ownership registry. */
internal object RuntimeSettingsGroups {
    const val POWER_BUTTON = "system.power_button"
    const val EXPERIMENTAL_CAPABILITIES = "system.experimental_capabilities"
    const val CAMERA = "camera.configuration"
    const val PHONE_NETWORK_TYPE = "phone.network_type"
    const val SETTINGS_DISPLAY_NETWORK = "settings.display_network"
    const val MISETTINGS_REFRESH_RATE = "misettings.refresh_rate"
    const val APP_REFRESH_RATES = "display.app_refresh_rates"
    const val MONET = "systemui.monet"
    const val MODULE_ENTRY = "settings.module_entry"
    const val ICONS = "systemui.icons"
    const val ISLANDS = "systemui.islands"
    const val NATIVE_PHOTO_PICKER = "system.native_photo_picker"
    // Retain the aggregate group ID so pre-split saved snapshots remain readable.
    const val NATIVE_FILE_PICKER = "system.native_picker"
    const val IME = "inputmethod.layout"
    const val PACKAGE_MANAGER = "system.package_manager"
    const val AOSP_INSTALLER = "system.aosp_installer"
    const val AOSP_SYSTEM_UI = "systemui.aosp_restore"
    const val AOSP_SECURITY = "securitycenter.aosp_restore"
    const val DARK_MODE = "system.dark_mode"
    const val FORCE_SCREENSHOT = "system.force_screenshot"
    const val SCREEN_RECORDER = "screenrecorder.configuration"
    const val SCREENSHOT = "screenshot.frame"
    const val BACK = "gesture.back"
    const val NATIVE = "launcher.native"
    const val VOLUME = "system.volume"
    const val XMSF = "xmsf.focus"
    const val AICR_FREEFORM = "aicr.freeform"
    const val FREEFORM = "system.freeform"
    const val GESTURE_BAR = "system.gesture_bar"
    const val GESTURE_BAR_UI = "systemui.gesture_bar"
    /** Switches that decide whether a hooker is installed at all, not just how it behaves. */
    const val GATED_FEATURES = "features.installation"
    const val APP_DETAILS = "securitycenter.app_details"
    const val SECURITY_GAMEBOOSTER = "securitycenter.gamebooster"
    const val SECURITY_POPUPS = "securitycenter.sidebar_popups"
    const val SECURITY_SIDEBAR = "securitycenter.sidebar"
    const val SECURITY_DOCK = "securitycenter.dock"
    const val SECURITY_DOCK_SHORTCUTS = "securitycenter.dock_shortcuts"
    const val SECURITY_CHECKS = "securitycenter.checks"
    const val SECURITY_PRIVACY = "securitycenter.privacy"
    const val SECURITY_WARNINGS = "securitycenter.warnings"
    const val SECURITY_BUBBLES = "securitycenter.bubbles"
    const val SECURITY_CLIPBOARD = "securitycenter.clipboard"
    const val SECURITY_APP_LOCK = "securitycenter.app_lock"
    const val OFFLINE_CONFIG = "carrier.offline_config"
    const val BUSINESS_HALL_ADS = "contacts.business_hall_ads"
    const val BARRAGE = "barrage.any_app"
    const val XIAOAI_GESTURES = "xiaoai.gesture_input"
    const val SETTINGS_AI = "settings.hyperos_ai"
    const val WIFI_DETAILS = "settings.wifi_details"
    const val ANALYTICS = "analytics.telemetry"
    const val PHRASE = "phrase.unlock_limits"
    const val SMS = "sms.auto_copy_code"
    const val MARKET = "market.purification"
    const val COMMUNITY = "community.feedback"
    const val SHARE_PANEL = "intentresolver.share_panel"
    /** Keyguard/AOD surface: SystemUI's keyguard battery row plus the AOD plugin rows. */
    const val AOD = "systemui.aod"
    /** Keyguard host surfaces whose policy is cached at install time (status bar, clock date, FOD). */
    const val KEYGUARD = "systemui.keyguard"
    /** Notification header layout, size and weather. */
    const val HEADER = "systemui.notification_header"
    /** Styling of already displayed notifications (clock seconds, Monet colour, absolute time, weight). */
    const val NOTIFICATION_TEXT = "systemui.notification_text"
    /** Media-card renderers in the host and in the control-center plugin. */
    const val MEDIA_CARD = "systemui.media_card"
    /** Control-center plugin sub-hooks whose presence a session used to decide at creation. */
    const val CONTROL_CENTER_PLUGIN = "systemui.control_center_plugin"
    /** Control-center privacy header styling (master, geometry, text and colors). */
    const val CONTROL_CENTER_PRIVACY = "systemui.control_center_privacy"
    /** Glass material tuner coefficients read from the resource/View funnels. */
    const val GLASS = "systemui.glass"
    /** Automatic power saving, its threshold and charging exit. */
    const val POWER_SAVE = "systemui.power_save"
    /** Lock-screen indication surfaces: bottom text, charging detail, fingerprint avoidance. */
    const val LOCKSCREEN = "systemui.lockscreen"
    const val AOD_CAPABILITY = "aod.capability"
    const val NOTIFICATION_ICONS = "systemui.notification_icons"
    const val NOTIFICATION_MINI_WINDOW = "systemui.notification_mini_window"
    /** Live floating-notification whitelist switch. */
    const val NOTIFICATION_WHITELIST = "systemui.notification_whitelist"
    const val FREEFORM_BLUR = "systemui.freeform_blur"
    const val MOTION_CUES_FREEFORM = "systemui.motion_cues_freeform"
    const val GOOGLE_APP = "google.omni"
    const val GOOGLE_SETTINGS = "settings.google_services"
    const val HTML_VIEWER_MILLET = "system.html_viewer_millet"
    const val GMS_RESTRICTIONS = "system.gms_restrictions"
    const val PASSKEY = "google.passkey"
    const val FCM = "google.fcm"
    const val POLICY_FEATURES = "features.policy"
    const val PASSIVE_POLICIES = "features.passive_policy"
    /** Download provider per-task focus notifications. */
    const val DOWNLOAD_FOCUS = "downloads.focus_notification"
    /** The plugin's full-screen flashlight page process. */
    const val FLASHLIGHT_PAGE = "plugin.flashlight_page"
    const val REVISION = "runtime_settings_revision"
    fun registerImeTargets(packages: Set<String>) = RuntimeSettingsDefinitions.registerImeTargets(packages)

    private val groups get() = RuntimeSettingsDefinitions.groups
    val backKeys get() = RuntimeSettingsDefinitions.backKeys
    /** Keys whose snapshot value must be a Boolean, declared per group as `strict`. */
    val strictBooleanKeys: Set<String> by lazy { groups.values.flatMapTo(HashSet()) { it.strict } }

    /** Built once: ownership of a key is a hash lookup plus the few policy matchers. */
    private class Index(groups: Map<String, RuntimeSettingsDefinitions.Group>) {
        val order: Map<String, Int> = groups.keys.withIndex().associate { (i, id) -> id to i }
        val byKey: Map<String, Set<String>> = HashMap<String, MutableSet<String>>().also { map ->
            groups.forEach { (id, group) -> group.keys.forEach { map.getOrPut(it) { LinkedHashSet() } += id } }
        }
        val matchers = groups.filterValues { it.matches != null }.toList()
    }
    private val index by lazy { Index(groups) }

    val targets: Set<String> get() = groups.values.flatMap { it.targets }.toSet()
    fun target(process: String, systemServer: Boolean = false): String = if (systemServer || process == "system_server") "android" else {
        val base = process.substringBefore(':')
        when {
            // OS4 manifests declare global process names as well as :private suffixes.
            base == "android.process.media" -> "com.android.providers.downloads"
            base in setOf("com.milink.crossdeviceservice", "com.milink.crossdeviceservice.file",
                "com.xiaomi.vtcamera", "com.milink.service.persistent", "com.milink.runtime") -> "com.milink.service"
            base == "com.android.thememanager.aiicon" -> "com.android.thememanager"
            // Every Security Center subprocess hosts part of the same feature set; before this,
            // `.power` was not normalized and therefore never received a settings snapshot at all
            // (its PowerSaveOverrideHooker and PowerRankingHooker only ever saw boot values).
            base in setOf("com.miui.securitycenter", "com.miui.securitycenter.remote",
                "com.miui.securitycenter.cache", "com.miui.securitycenter.power",
                "com.miui.securitycenter.bootaware", "com.miui.securitycenter.uniauth") -> "com.miui.securitycenter"
            else -> base
        }
    }
    fun forTarget(target: String): Set<String> = groups.filterValues { target in it.targets }.keys
    fun forProcess(process: String, systemServer: Boolean = false): Set<String> = forTarget(target(process, systemServer))
    /** Owning groups in declaration order, which dispatch follows. */
    fun owners(key: String): Set<String> {
        val direct = index.byKey[key].orEmpty()
        val matched = index.matchers.filter { (_, group) -> group.matches!!.invoke(key) }
        if (matched.isEmpty()) return direct
        return (direct + matched.map { it.first }).sortedBy { index.order[it] }.toCollection(LinkedHashSet())
    }
    fun owns(key: String): Boolean = owners(key).isNotEmpty()
    fun owns(target: String, key: String): Boolean = owners(key).any { target in groups.getValue(it).targets }
    fun affected(key: String?): Set<String> = if (key == null || key == "prefs_epoch") groups.keys
        else owners(key)
    fun changed(before: Map<String, Any>, after: Map<String, Any>): Set<String> =
        (before.keys + after.keys).filter {
            // Ordering/commit identities are carried by the snapshot, not UI invalidations.
            it != "prefs_epoch" && it != PlatformSettingsKeys.KEY_NATIVE_RULE_REVISION && before[it] != after[it]
        }.flatMap(::affected).toSet()
    fun subset(target: String, values: Map<String, Any>): Map<String, Any> = values.filterKeys { owns(target, it) }
}
