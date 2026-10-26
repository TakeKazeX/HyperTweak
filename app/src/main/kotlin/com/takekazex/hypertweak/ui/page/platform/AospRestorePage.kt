package com.takekazex.hypertweak.ui.page.platform

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.util.platform.ExtendUnlockLauncher
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

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
    settingsGlobalInterface: Boolean,
    onSettingsGlobalInterfaceChange: (Boolean) -> Unit
) {
    val settingsStore = LocalAppSettingsStore.current
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current

    var packageInstaller by remember {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_PACKAGE_INSTALLER, false))
    }
    var powerMenu by remember {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_POWER_MENU, false))
    }
    var volumePanel by remember {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_VOLUME_PANEL, false))
    }
    var volumePanelHapticMiui by remember {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_VOLUME_HAPTIC_MIUI, false))
    }
    var clipboardEditor by remember {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_CLIPBOARD_EDITOR, false))
    }
    var extendUnlockFix by remember {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_EXTEND_UNLOCK_FIX, false))
    }
    var appInfoEntry by remember {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_APP_INFO_ENTRY, false))
    }
    var appManagerEntry by remember {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_AOSP_APP_MANAGER_ENTRY, false))
    }
    var onDeviceModelEntry by remember {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_SETTINGS_ONDEVICE_MODEL_ENTRY, false))
    }
    var geminiEntry by remember {
        mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_SETTINGS_GEMINI_ENTRY, false))
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.aosp_page_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.aosp_back)) } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))

            SmallTitle(stringResource(R.string.aosp_section_system))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    NativePickerPreferences()
                    SwitchPreference(
                        checked = packageInstaller,
                        onCheckedChange = { enabled ->
                            packageInstaller = enabled
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_AOSP_PACKAGE_INSTALLER, enabled)
                        },
                        title = stringResource(R.string.aosp_package_installer),
                        summary = stringResource(R.string.aosp_package_installer_summary)
                    )
                }
            }

            SmallTitle(stringResource(R.string.aosp_section_international))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = settingsGlobalInterface,
                        onCheckedChange = onSettingsGlobalInterfaceChange,
                        title = stringResource(R.string.settings_global_interface_title),
                        summary = stringResource(R.string.settings_global_interface_summary)
                    )
                    SwitchPreference(
                        checked = onDeviceModelEntry,
                        onCheckedChange = { enabled ->
                            onDeviceModelEntry = enabled
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_SETTINGS_ONDEVICE_MODEL_ENTRY, enabled)
                        },
                        title = stringResource(R.string.settings_ondevice_model_entry_title),
                        summary = stringResource(R.string.settings_ondevice_model_entry_summary)
                    )
                    SwitchPreference(
                        checked = geminiEntry,
                        onCheckedChange = { enabled ->
                            geminiEntry = enabled
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_SETTINGS_GEMINI_ENTRY, enabled)
                        },
                        title = stringResource(R.string.settings_gemini_entry_title),
                        summary = stringResource(R.string.settings_gemini_entry_summary)
                    )
                }
            }

            SmallTitle(stringResource(R.string.aosp_section_system_ui))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = powerMenu,
                        onCheckedChange = { enabled ->
                            powerMenu = enabled
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_AOSP_POWER_MENU, enabled)
                        },
                        title = stringResource(R.string.aosp_power_menu),
                        summary = stringResource(R.string.aosp_power_menu_summary)
                    )
                    SwitchPreference(
                        checked = volumePanel,
                        onCheckedChange = { enabled ->
                            volumePanel = enabled
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_AOSP_VOLUME_PANEL, enabled)
                        },
                        title = stringResource(R.string.aosp_volume_panel),
                        summary = stringResource(R.string.aosp_volume_panel_summary)
                    )
                    SwitchPreference(
                        checked = volumePanelHapticMiui,
                        onCheckedChange = { enabled ->
                            volumePanelHapticMiui = enabled
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_AOSP_VOLUME_HAPTIC_MIUI, enabled)
                        },
                        enabled = volumePanel,
                        title = stringResource(R.string.aosp_volume_haptic_miui),
                        summary = stringResource(R.string.aosp_volume_haptic_miui_summary)
                    )
                    SwitchPreference(
                        checked = extendUnlockFix,
                        onCheckedChange = { enabled ->
                            extendUnlockFix = enabled
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_EXTEND_UNLOCK_FIX, enabled)
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
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_AOSP_CLIPBOARD_EDITOR, enabled)
                        },
                        title = stringResource(R.string.aosp_clipboard_editor),
                        summary = stringResource(R.string.aosp_clipboard_editor_summary)
                    )

                }
            }

            SmallTitle(stringResource(R.string.aosp_section_security_center))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = appInfoEntry,
                        onCheckedChange = { enabled ->
                            appInfoEntry = enabled
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_AOSP_APP_INFO_ENTRY, enabled)
                        },
                        title = stringResource(R.string.aosp_app_info_entry),
                        summary = stringResource(R.string.aosp_app_info_entry_summary)
                    )
                    SwitchPreference(
                        checked = appManagerEntry,
                        onCheckedChange = { enabled ->
                            appManagerEntry = enabled
                            settingsStore.putBoolean(PlatformSettingsKeys.KEY_AOSP_APP_MANAGER_ENTRY, enabled)
                        },
                        title = stringResource(R.string.aosp_app_manager_entry),
                        summary = stringResource(R.string.aosp_app_manager_entry_summary)
                    )

                }
            }

            SmallTitle(stringResource(R.string.aosp_section_input_method))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.aosp_keyboard_bar),
                    summary = stringResource(R.string.aosp_keyboard_bar_summary),
                    onClick = onNavigateToAospIme
                )
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
