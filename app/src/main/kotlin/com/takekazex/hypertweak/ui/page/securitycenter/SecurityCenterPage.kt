package com.takekazex.hypertweak.ui.page.securitycenter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.AppSettingsKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.ui.page.glass.DockSlider
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Security Center-scoped feature switches, grouped independently from AOSP restoration. */
@Composable
fun SecurityCenterPage(
    onBack: () -> Unit,
    onNavigateToPowerSave: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    val settingsStore = LocalAppSettingsStore.current

    var sidebarMode by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_SIDEBAR_MODE, settingsStore::putInt) { settingsStore.getInt(PlatformSettingsKeys.KEY_SECURITY_CENTER_SIDEBAR_MODE, 0).takeIf { it in 0..2 } ?: 0 }
    var forceGunsight by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_FORCE_UNLOCK_GUNSIGHT, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_FORCE_UNLOCK_GUNSIGHT, false) }
    var forceMacro by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_FORCE_UNLOCK_MACRO, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_FORCE_UNLOCK_MACRO, false) }
    var hideDolbyPopup by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_DOLBY_POPUP, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_DOLBY_POPUP, false) }
    var hideGamePopup by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_GAME_POPUP, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_GAME_POPUP, false) }
    var dockUnlimited by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PINNED, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PINNED, false) }
    var dockUnlimitedPredictions by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PREDICTIONS, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_UNLIMITED_PREDICTIONS, false) }
    var dockAppShortcuts by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_APP_SHORTCUTS, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_APP_SHORTCUTS, false) }
    var dockAppShortcutsFreeform by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_APP_SHORTCUTS_FREEFORM, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_APP_SHORTCUTS_FREEFORM, false) }
    var dockPredictions by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_PREDICT_LIMIT, settingsStore::putInt) { settingsStore.getInt(PlatformSettingsKeys.KEY_SECURITY_CENTER_DOCK_PREDICT_LIMIT, 8).coerceIn(0, 15) }
    var iconLaunch by rememberStoredSetting(AppSettingsKeys.KEY_APP_DETAILS_ICON_LAUNCH, settingsStore::putBoolean) { settingsStore.getBoolean(AppSettingsKeys.KEY_APP_DETAILS_ICON_LAUNCH, false) }
    var hideReport by rememberStoredSetting(AppSettingsKeys.KEY_APP_DETAILS_HIDE_REPORT, settingsStore::putBoolean) { settingsStore.getBoolean(AppSettingsKeys.KEY_APP_DETAILS_HIDE_REPORT, false) }
    var allowDisable by rememberStoredSetting(AppSettingsKeys.KEY_APP_DETAILS_ALLOW_DISABLE, settingsStore::putBoolean) { settingsStore.getBoolean(AppSettingsKeys.KEY_APP_DETAILS_ALLOW_DISABLE, false) }
    var moreAppInfo by rememberStoredSetting(AppSettingsKeys.KEY_APP_DETAILS_MORE_INFO, settingsStore::putBoolean) { settingsStore.getBoolean(AppSettingsKeys.KEY_APP_DETAILS_MORE_INFO, false) }
    var appLinkManagement by rememberStoredSetting(AppSettingsKeys.KEY_APP_LINK_MANAGEMENT, settingsStore::putBoolean) {
        settingsStore.getBoolean(AppSettingsKeys.KEY_APP_LINK_MANAGEMENT, false)
    }
    var namedApkShare by rememberStoredSetting(AppSettingsKeys.KEY_APP_DETAILS_NAMED_SHARE, settingsStore::putBoolean) { settingsStore.getBoolean(AppSettingsKeys.KEY_APP_DETAILS_NAMED_SHARE, false) }
    var bubbleNotificationUnlock by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_BUBBLE_NOTIFICATION_UNLOCK, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_BUBBLE_NOTIFICATION_UNLOCK, false) }
    var lowBatteryWarningMode by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_LOW_BATTERY_MODE, settingsStore::putInt) {
        when {
            settingsStore.contains(PlatformSettingsKeys.KEY_SECURITY_CENTER_LOW_BATTERY_MODE) -> settingsStore.getInt(
                PlatformSettingsKeys.KEY_SECURITY_CENTER_LOW_BATTERY_MODE,
                PlatformSettingsKeys.SECURITY_CENTER_LOW_BATTERY_FOLLOW,
            ).coerceIn(PlatformSettingsKeys.SECURITY_CENTER_LOW_BATTERY_FOLLOW, PlatformSettingsKeys.SECURITY_CENTER_LOW_BATTERY_SILENT)
            settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_HIDE_LOW_BATTERY_WARNING, false) -> PlatformSettingsKeys.SECURITY_CENTER_LOW_BATTERY_SILENT
            else -> PlatformSettingsKeys.SECURITY_CENTER_LOW_BATTERY_FOLLOW
        }
    }
    var permissionWarningMode by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_PERMISSION_WARNING_MODE, settingsStore::putInt) { settingsStore.getInt(PlatformSettingsKeys.KEY_SECURITY_CENTER_PERMISSION_WARNING_MODE, PlatformSettingsKeys.SECURITY_CENTER_WARNING_FOLLOW_SYSTEM).coerceIn(PlatformSettingsKeys.SECURITY_CENTER_WARNING_FOLLOW_SYSTEM, PlatformSettingsKeys.SECURITY_CENTER_WARNING_DIRECT_APPROVE) }
    var showDetailedPowerData by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_SHOW_DETAILED_POWER_DATA, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_SHOW_DETAILED_POWER_DATA, false) }
    var restorePowerRanking by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_RESTORE_POWER_RANKING, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_RESTORE_POWER_RANKING, false) }
    var showBerserkMode by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_SHOW_BERSERK_MODE, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_SHOW_BERSERK_MODE, false) }
    var moreBatteryInfo by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_MORE_BATTERY_INFO, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_MORE_BATTERY_INFO, false) }
    var appLockScramblePin by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_APP_LOCK_SCRAMBLE_PIN, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_APP_LOCK_SCRAMBLE_PIN, false) }
    var privacyCallEntry by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_PRIVACY_CALL_ENTRY, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_PRIVACY_CALL_ENTRY, false) }
    var antiPeepingMode by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_ANTI_PEEPING_MODE, settingsStore::putInt) { settingsStore.getInt(PlatformSettingsKeys.KEY_SECURITY_CENTER_ANTI_PEEPING_MODE, PlatformSettingsKeys.SECURITY_CENTER_ANTI_PEEPING_SYSTEM_DEFAULT).coerceIn(PlatformSettingsKeys.SECURITY_CENTER_ANTI_PEEPING_SYSTEM_DEFAULT, PlatformSettingsKeys.SECURITY_CENTER_ANTI_PEEPING_V2) }
    var frontCameraAssistantEntry by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_FRONT_CAMERA_ASSISTANT_ENTRY, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_FRONT_CAMERA_ASSISTANT_ENTRY, false) }
    var callPrivacyCameraEntry by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_CALL_PRIVACY_CAMERA_ENTRY, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_CALL_PRIVACY_CAMERA_ENTRY, false) }
    var skipLocalSuCheck by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_SKIP_LOCAL_SU_CHECK, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_SKIP_LOCAL_SU_CHECK, false) }
    var disableSafePayAutoScan by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_SAFEPAY_AUTO_SCAN, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_SAFEPAY_AUTO_SCAN, false) }
    var disableRootScanItem by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_ROOT_SCAN_ITEM, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_ROOT_SCAN_ITEM, false) }
    var disableUsbInstallIntercept by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_USB_INSTALL_INTERCEPT, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_DISABLE_USB_INSTALL_INTERCEPT, false) }
    var bypassUsbInstallNetworkVerify by rememberStoredSetting(PlatformSettingsKeys.KEY_SECURITY_CENTER_BYPASS_USB_INSTALL_NETWORK_VERIFY, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SECURITY_CENTER_BYPASS_USB_INSTALL_NETWORK_VERIFY, false) }
    var networkAssistantIgnoreOffline by rememberStoredSetting(PlatformSettingsKeys.KEY_NETWORK_ASSISTANT_IGNORE_OFFLINE, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_NETWORK_ASSISTANT_IGNORE_OFFLINE, false) }
    var businessHallAdBlock by rememberStoredSetting(PlatformSettingsKeys.KEY_CONTACTS_BUSINESS_HALL_AD_BLOCK, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_CONTACTS_BUSINESS_HALL_AD_BLOCK, false) }
    var contactsBusinessHallIgnoreOffline by rememberStoredSetting(PlatformSettingsKeys.KEY_CONTACTS_BUSINESS_HALL_IGNORE_OFFLINE, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_CONTACTS_BUSINESS_HALL_IGNORE_OFFLINE, false) }
    AppScrollPage(
        title = stringResource(R.string.security_center_page_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.aosp_back),
    ) {

        MissingScopeCard("com.miui.securitycenter", "com.miui.securitycore", "com.android.contacts")
        AppSection(title = stringResource(R.string.security_center_app_details_section)) {
            SwitchPreference(
                checked = iconLaunch,
                onCheckedChange = { enabled ->
                    iconLaunch = enabled
                },
                title = stringResource(R.string.security_center_icon_launch),
                summary = stringResource(R.string.security_center_icon_launch_summary)
            )
            SwitchPreference(
                checked = moreAppInfo,
                onCheckedChange = { enabled ->
                    moreAppInfo = enabled
                },
                title = stringResource(R.string.security_center_more_app_info),
                summary = stringResource(R.string.security_center_more_app_info_summary)
            )
            SwitchPreference(
                checked = appLinkManagement,
                onCheckedChange = { appLinkManagement = it },
                title = stringResource(R.string.security_center_app_link_management),
                summary = stringResource(R.string.security_center_app_link_management_summary),
            )
            SwitchPreference(
                checked = namedApkShare,
                onCheckedChange = { enabled ->
                    namedApkShare = enabled
                },
                title = stringResource(R.string.security_center_named_apk_share),
                summary = stringResource(R.string.security_center_named_apk_share_summary)
            )
            SwitchPreference(
                checked = hideReport,
                onCheckedChange = { enabled ->
                    hideReport = enabled
                },
                title = stringResource(R.string.security_center_hide_app_report),
                summary = stringResource(R.string.security_center_hide_app_report_summary)
            )
            SwitchPreference(
                checked = allowDisable,
                onCheckedChange = { enabled ->
                    allowDisable = enabled
                },
                title = stringResource(R.string.security_center_allow_app_disable),
                summary = stringResource(R.string.security_center_allow_app_disable_summary)
            )
        }
        AppSection(title = stringResource(R.string.security_center_privacy_entries_section)) {
            SwitchPreference(
                checked = privacyCallEntry,
                onCheckedChange = { enabled ->
                    privacyCallEntry = enabled
                },
                title = stringResource(R.string.security_center_privacy_call_entry),
                summary = stringResource(R.string.security_center_privacy_call_entry_summary)
            )

            OverlayDropdownPreference(
                items = listOf(
                    stringResource(R.string.security_center_anti_peeping_system_default),
                    stringResource(R.string.security_center_anti_peeping_v1),
                    stringResource(R.string.security_center_anti_peeping_v2)
                ),
                selectedIndex = antiPeepingMode,
                onSelectedIndexChange = { mode ->
                    antiPeepingMode = mode
                },
                title = stringResource(R.string.security_center_anti_peeping_mode),
                summary = stringResource(R.string.security_center_anti_peeping_mode_summary)
            )

            SwitchPreference(
                checked = frontCameraAssistantEntry,
                onCheckedChange = { enabled ->
                    frontCameraAssistantEntry = enabled
                },
                title = stringResource(R.string.security_center_front_camera_assistant_entry),
                summary = stringResource(
                    R.string.security_center_front_camera_assistant_entry_summary
                )
            )

            SwitchPreference(
                checked = callPrivacyCameraEntry,
                onCheckedChange = { enabled ->
                    callPrivacyCameraEntry = enabled
                },
                title = stringResource(R.string.security_center_call_privacy_camera_entry),
                summary = stringResource(
                    R.string.security_center_call_privacy_camera_entry_summary
                )
            )
        }

        AppSection(title = stringResource(R.string.security_center_app_lock_section)) {
            SwitchPreference(
                checked = appLockScramblePin,
                onCheckedChange = { enabled ->
                    appLockScramblePin = enabled
                },
                title = stringResource(R.string.security_center_app_lock_scramble_pin),
                summary = stringResource(R.string.security_center_app_lock_scramble_pin_summary)
            )
        }

        AppSection(title = stringResource(R.string.security_center_dock_section)) {
            OverlayDropdownPreference(
                items = listOf(
                    stringResource(R.string.security_center_sidebar_native),
                    stringResource(R.string.security_center_sidebar_disabled),
                    stringResource(R.string.security_center_sidebar_invisible)
                ),
                selectedIndex = sidebarMode,
                onSelectedIndexChange = {
                    sidebarMode = it
                },
                title = stringResource(R.string.security_center_sidebar_mode),
                summary = stringResource(R.string.security_center_sidebar_mode_summary)
            )
            SwitchPreference(
                checked = hideGamePopup,
                onCheckedChange = {
                    hideGamePopup = it
                },
                title = stringResource(R.string.security_center_hide_game_popup),
                summary = stringResource(R.string.security_center_hide_game_popup_summary)
            )
            SwitchPreference(
                checked = hideDolbyPopup,
                onCheckedChange = {
                    hideDolbyPopup = it
                },
                title = stringResource(R.string.security_center_hide_dolby_popup),
                summary = stringResource(R.string.security_center_hide_dolby_popup_summary)
            )
            SwitchPreference(
                checked = forceGunsight,
                onCheckedChange = {
                    forceGunsight = it
                },
                title = stringResource(R.string.security_center_force_gunsight),
                summary = stringResource(R.string.security_center_force_gunsight_summary)
            )
            SwitchPreference(
                checked = forceMacro,
                onCheckedChange = {
                    forceMacro = it
                },
                title = stringResource(R.string.security_center_force_macro),
                summary = stringResource(R.string.security_center_force_macro_summary)
            )
            SwitchPreference(
                checked = dockUnlimited,
                onCheckedChange = {
                    dockUnlimited = it
                },
                title = stringResource(R.string.security_center_dock_unlimited),
                summary = stringResource(R.string.security_center_dock_unlimited_summary)
            )
            SwitchPreference(
                checked = dockUnlimitedPredictions,
                onCheckedChange = {
                    dockUnlimitedPredictions = it
                },
                title = stringResource(R.string.security_center_dock_unlimited_predictions),
                summary = stringResource(R.string.security_center_dock_unlimited_predictions_summary)
            )
            SwitchPreference(
                checked = dockAppShortcuts,
                onCheckedChange = {
                    dockAppShortcuts = it
                },
                title = stringResource(R.string.security_center_dock_app_shortcuts),
                summary = stringResource(R.string.security_center_dock_app_shortcuts_summary)
            )
            SwitchPreference(
                checked = dockAppShortcutsFreeform,
                onCheckedChange = {
                    dockAppShortcutsFreeform = it
                },
                enabled = dockAppShortcuts,
                title = stringResource(R.string.security_center_dock_app_shortcuts_freeform),
                summary = stringResource(R.string.security_center_dock_app_shortcuts_freeform_summary)
            )
            DockSlider(
                title = stringResource(R.string.security_center_dock_predictions),
                value = dockPredictions, range = 0..15, defaultValue = 8, unit = "",
                onChange = {
                    if (dockPredictions != it) {
                        dockPredictions = it
                    }
                },
                summary = stringResource(R.string.security_center_dock_predictions_summary)
            )
        }

        AppSection(title = stringResource(R.string.security_center_checks_section)) {
            SwitchPreference(
                checked = skipLocalSuCheck,
                onCheckedChange = { enabled ->
                    skipLocalSuCheck = enabled
                },
                title = stringResource(R.string.security_center_skip_local_su_check),
                summary = stringResource(R.string.security_center_skip_local_su_check_summary)
            )
            SwitchPreference(
                checked = disableSafePayAutoScan,
                onCheckedChange = { enabled ->
                    disableSafePayAutoScan = enabled
                },
                title = stringResource(R.string.security_center_disable_safepay_auto_scan),
                summary = stringResource(R.string.security_center_disable_safepay_auto_scan_summary)
            )
            SwitchPreference(
                checked = disableRootScanItem,
                onCheckedChange = { enabled ->
                    disableRootScanItem = enabled
                },
                title = stringResource(R.string.security_center_disable_root_scan_item),
                summary = stringResource(R.string.security_center_disable_root_scan_item_summary)
            )
            SwitchPreference(
                checked = disableUsbInstallIntercept,
                onCheckedChange = { enabled ->
                    disableUsbInstallIntercept = enabled
                },
                title = stringResource(R.string.security_center_disable_usb_install_intercept),
                summary = stringResource(R.string.security_center_disable_usb_install_intercept_summary)
            )
            SwitchPreference(
                checked = bypassUsbInstallNetworkVerify,
                onCheckedChange = { enabled ->
                    bypassUsbInstallNetworkVerify = enabled
                },
                title = stringResource(R.string.security_center_bypass_usb_install_network_verify),
                summary = stringResource(R.string.security_center_bypass_usb_install_network_verify_summary)
            )
        }

        AppSection(title = stringResource(R.string.security_center_carrier_offline_section)) {
            SwitchPreference(
                checked = networkAssistantIgnoreOffline,
                onCheckedChange = { enabled ->
                    networkAssistantIgnoreOffline = enabled
                },
                title = stringResource(R.string.security_center_network_assistant_ignore_offline),
                summary = stringResource(R.string.security_center_carrier_offline_summary)
            )
            SwitchPreference(
                checked = contactsBusinessHallIgnoreOffline,
                onCheckedChange = { enabled ->
                    contactsBusinessHallIgnoreOffline = enabled
                },
                title = stringResource(R.string.security_center_contacts_business_hall_ignore_offline),
                summary = stringResource(R.string.security_center_carrier_offline_summary)
            )
            SwitchPreference(
                checked = businessHallAdBlock,
                onCheckedChange = { enabled ->
                    businessHallAdBlock = enabled
                },
                title = stringResource(R.string.security_center_business_hall_ad_block),
                summary = stringResource(R.string.security_center_business_hall_ad_block_summary),
            )
        }

        AppSection(title = stringResource(R.string.security_center_clipboard_section)) {
            SwitchPreference(
                checked = appSettings.lbeClipboardToast,
                onCheckedChange = { appSettings.lbeClipboardToast = it },
                title = stringResource(R.string.tweaks_lbe_clipboard_toast_title),
                summary = stringResource(R.string.tweaks_lbe_clipboard_toast_summary)
            )
        }

        AppSection(title = stringResource(R.string.security_center_notifications_section)) {
            SwitchPreference(
                checked = bubbleNotificationUnlock,
                onCheckedChange = { enabled ->
                    bubbleNotificationUnlock = enabled
                },
                title = stringResource(R.string.security_bubble_notification_unlock),
                summary = stringResource(R.string.security_bubble_notification_unlock_summary)
            )
        }

        AppSection(title = stringResource(R.string.security_center_warning_section)) {
            OverlayDropdownPreference(
                items = listOf(
                    stringResource(R.string.security_center_warning_follow_system),
                    stringResource(R.string.security_center_warning_skip_countdown),
                    stringResource(R.string.security_center_warning_direct_approve)
                ),
                selectedIndex = permissionWarningMode,
                onSelectedIndexChange = { mode ->
                    permissionWarningMode = mode
                },
                title = stringResource(R.string.security_center_warning_mode),
                summary = stringResource(R.string.security_center_warning_mode_summary)
            )
        }

        AppSection(title = stringResource(R.string.security_center_battery_section)) {
            ArrowPreference(
                title = stringResource(R.string.power_save_override_title),
                summary = stringResource(R.string.power_save_override_entry_summary),
                onClick = onNavigateToPowerSave,
            )
            OverlayDropdownPreference(
                items = listOf(
                    stringResource(R.string.security_center_low_battery_follow),
                    stringResource(R.string.security_center_low_battery_hide_dialog),
                    stringResource(R.string.security_center_low_battery_silent)
                ),
                selectedIndex = lowBatteryWarningMode,
                onSelectedIndexChange = { mode ->
                    lowBatteryWarningMode = mode
                },
                title = stringResource(R.string.security_center_hide_low_battery_warning),
                summary = stringResource(R.string.security_center_hide_low_battery_warning_summary)
            )
            SwitchPreference(
                checked = showDetailedPowerData,
                onCheckedChange = { enabled ->
                    showDetailedPowerData = enabled
                },
                title = stringResource(R.string.security_center_show_detailed_power_data),
                summary = stringResource(R.string.security_center_show_detailed_power_data_summary)
            )
            SwitchPreference(
                checked = restorePowerRanking,
                onCheckedChange = { enabled ->
                    restorePowerRanking = enabled
                },
                title = stringResource(R.string.security_center_restore_power_ranking),
                summary = stringResource(R.string.security_center_restore_power_ranking_summary)
            )
            SwitchPreference(
                checked = showBerserkMode,
                onCheckedChange = { enabled ->
                    showBerserkMode = enabled
                },
                title = stringResource(R.string.security_center_show_berserk_mode),
                summary = stringResource(R.string.security_center_show_berserk_mode_summary)
            )
            SwitchPreference(
                checked = moreBatteryInfo,
                onCheckedChange = { enabled ->
                    moreBatteryInfo = enabled
                },
                title = stringResource(R.string.security_center_more_battery_info),
                summary = stringResource(R.string.security_center_more_battery_info_summary)
            )
        }
    }
}
