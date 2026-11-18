package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.util.platform.ExtendUnlockLauncher
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * Toggles that hand a HyperOS component back to its AOSP implementation.
 *
 * Most state is kept locally; the Settings-interface switch stays hoisted in `MainActivity` so its
 * existing restart tracking is preserved. Other switches use the runtime settings dispatcher.
 */
@Composable
fun AospRestorePage(
    onBack: () -> Unit,
    onNavigateToAospIme: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    val settingsStore = LocalAppSettingsStore.current
    val context = LocalContext.current

    var packageInstaller by rememberStoredSetting(PlatformSettingsKeys.KEY_AOSP_PACKAGE_INSTALLER, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_PACKAGE_INSTALLER, false) }
    var powerMenu by rememberStoredSetting(PlatformSettingsKeys.KEY_AOSP_POWER_MENU, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_POWER_MENU, false) }
    var volumePanel by rememberStoredSetting(PlatformSettingsKeys.KEY_AOSP_VOLUME_PANEL, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_VOLUME_PANEL, false) }
    var volumePanelHapticMiui by rememberStoredSetting(PlatformSettingsKeys.KEY_AOSP_VOLUME_HAPTIC_MIUI, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_VOLUME_HAPTIC_MIUI, false) }
    var clipboardEditor by rememberStoredSetting(PlatformSettingsKeys.KEY_AOSP_CLIPBOARD_EDITOR, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_CLIPBOARD_EDITOR, false) }
    var extendUnlockFix by rememberStoredSetting(PlatformSettingsKeys.KEY_EXTEND_UNLOCK_FIX, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_EXTEND_UNLOCK_FIX, false) }
    var appInfoEntry by rememberStoredSetting(PlatformSettingsKeys.KEY_AOSP_APP_INFO_ENTRY, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_APP_INFO_ENTRY, false) }
    var appManagerEntry by rememberStoredSetting(PlatformSettingsKeys.KEY_AOSP_APP_MANAGER_ENTRY, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_APP_MANAGER_ENTRY, false) }
    var onDeviceModelEntry by rememberStoredSetting(PlatformSettingsKeys.KEY_SETTINGS_ONDEVICE_MODEL_ENTRY, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SETTINGS_ONDEVICE_MODEL_ENTRY, false) }
    var geminiEntry by rememberStoredSetting(PlatformSettingsKeys.KEY_SETTINGS_GEMINI_ENTRY, settingsStore::putBoolean) { settingsStore.getBoolean(PlatformSettingsKeys.KEY_SETTINGS_GEMINI_ENTRY, false) }

    AppScrollPage(
        title = stringResource(R.string.aosp_page_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.aosp_back),
    ) {
        MissingScopeCard("com.android.settings", "system", "com.android.systemui", "com.miui.securitycenter", "com.miui.securitycore", "com.android.photopicker")

        AppSection(title = stringResource(R.string.aosp_section_system)) {
            NativePickerPreferences()
            SwitchPreference(
                checked = packageInstaller,
                onCheckedChange = { enabled ->
                    packageInstaller = enabled
                },
                title = stringResource(R.string.aosp_package_installer),
                summary = stringResource(R.string.aosp_package_installer_summary)
            )
        }

        AppSection(title = stringResource(R.string.aosp_section_international)) {
            SwitchPreference(
                checked = appSettings.settingsGlobalInterface,
                onCheckedChange = { appSettings.settingsGlobalInterface = it },
                title = stringResource(R.string.settings_global_interface_title),
                summary = stringResource(R.string.settings_global_interface_summary)
            )
            SwitchPreference(
                checked = onDeviceModelEntry,
                onCheckedChange = { enabled ->
                    onDeviceModelEntry = enabled
                },
                title = stringResource(R.string.settings_ondevice_model_entry_title),
                summary = stringResource(R.string.settings_ondevice_model_entry_summary)
            )
            SwitchPreference(
                checked = geminiEntry,
                onCheckedChange = { enabled ->
                    geminiEntry = enabled
                },
                title = stringResource(R.string.settings_gemini_entry_title),
                summary = stringResource(R.string.settings_gemini_entry_summary)
            )
        }

        AppSection(title = stringResource(R.string.aosp_section_system_ui)) {
            SwitchPreference(
                checked = powerMenu,
                onCheckedChange = { enabled ->
                    powerMenu = enabled
                },
                title = stringResource(R.string.aosp_power_menu),
                summary = stringResource(R.string.aosp_power_menu_summary)
            )
            SwitchPreference(
                checked = volumePanel,
                onCheckedChange = { enabled ->
                    volumePanel = enabled
                },
                title = stringResource(R.string.aosp_volume_panel),
                summary = stringResource(R.string.aosp_volume_panel_summary)
            )
            SwitchPreference(
                checked = volumePanelHapticMiui,
                onCheckedChange = { enabled ->
                    volumePanelHapticMiui = enabled
                },
                enabled = volumePanel,
                title = stringResource(R.string.aosp_volume_haptic_miui),
                summary = stringResource(R.string.aosp_volume_haptic_miui_summary)
            )
            SwitchPreference(
                checked = extendUnlockFix,
                onCheckedChange = { enabled ->
                    extendUnlockFix = enabled
                },
                title = stringResource(R.string.aosp_extend_unlock),
                summary = stringResource(R.string.aosp_extend_unlock_summary)
            )
            ArrowPreference(
                title = stringResource(R.string.aosp_configure_extend_unlock),
                summary = stringResource(R.string.aosp_configure_extend_unlock_summary),
                enabled = extendUnlockFix,
                onClick = { ExtendUnlockLauncher.launch(context) }
            )
            SwitchPreference(
                checked = clipboardEditor,
                onCheckedChange = { enabled ->
                    clipboardEditor = enabled
                },
                title = stringResource(R.string.aosp_clipboard_editor),
                summary = stringResource(R.string.aosp_clipboard_editor_summary)
            )
        }

        AppSection(title = stringResource(R.string.aosp_section_security_center)) {
            SwitchPreference(
                checked = appInfoEntry,
                onCheckedChange = { enabled ->
                    appInfoEntry = enabled
                },
                title = stringResource(R.string.aosp_app_info_entry),
                summary = stringResource(R.string.aosp_app_info_entry_summary)
            )
            SwitchPreference(
                checked = appManagerEntry,
                onCheckedChange = { enabled ->
                    appManagerEntry = enabled
                },
                title = stringResource(R.string.aosp_app_manager_entry),
                summary = stringResource(R.string.aosp_app_manager_entry_summary)
            )
        }

        AppSection(title = stringResource(R.string.aosp_section_input_method)) {
            ArrowPreference(
                title = stringResource(R.string.aosp_keyboard_bar),
                summary = stringResource(R.string.aosp_keyboard_bar_summary),
                onClick = onNavigateToAospIme
            )
        }
    }
}
