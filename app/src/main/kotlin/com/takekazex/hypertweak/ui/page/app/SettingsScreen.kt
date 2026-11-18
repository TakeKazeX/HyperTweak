package com.takekazex.hypertweak.ui.page.app

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun SettingsScreenContent(
    padding: PaddingValues,
    hideLauncherIcon: Boolean,
    onHideLauncherIconChange: (Boolean) -> Unit,
    onNavigateToScopePrompts: () -> Unit,
    onNavigateToBackupRestore: () -> Unit,
    onNavigateToExperimentalFeatures: () -> Unit,
    themeSummary: String,
    onNavigateToAppearance: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToAppShortcuts: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    val context = LocalContext.current
    AppScrollPage(
        title = stringResource(R.string.settings_settings),
        onBack = null,
        bottomInset = padding.calculateBottomPadding(),
    ) {

        AppSection(stringResource(R.string.settings_appearance)) {
            ArrowPreference(
                title = stringResource(R.string.settings_appearance),
                summary = themeSummary,
                onClick = onNavigateToAppearance
            )
        }

        // Module Preferences
        AppSection(stringResource(R.string.settings_module_preferences)) {
            SwitchPreference(
                checked = appSettings.showInSettings,
                onCheckedChange = { appSettings.showInSettings = it },
                title = stringResource(R.string.settings_show_entry_in_system_settings),
                summary = stringResource(R.string.settings_show_entry_in_system_settings_summary)
            )

            SwitchPreference(
                checked = hideLauncherIcon,
                onCheckedChange = onHideLauncherIconChange,
                title = stringResource(R.string.settings_hide_desktop_icon),
                summary = stringResource(R.string.settings_hide_desktop_icon_summary)
            )

            MiuixExpandableContent(visible = !hideLauncherIcon) {
                ArrowPreference(
                    title = stringResource(R.string.settings_app_shortcuts),
                    summary = stringResource(R.string.settings_app_shortcuts_summary),
                    onClick = onNavigateToAppShortcuts
                )
            }

            SwitchPreference(
                checked = appSettings.allowLandscape,
                onCheckedChange = { appSettings.allowLandscape = it },
                title = stringResource(R.string.settings_allow_landscape),
                summary = stringResource(R.string.settings_allow_landscape_summary)
            )

            OverlayDropdownPreference(
                title = stringResource(id = R.string.pref_language_title),
                items = listOf(
                    stringResource(id = R.string.pref_language_device_default),
                    stringResource(id = R.string.pref_language_zh_cn),
                    stringResource(id = R.string.pref_language_en)
                ),
                selectedIndex = appSettings.appLanguage,
                onSelectedIndexChange = { appSettings.appLanguage = it }
            )
        }

        // Other: the less frequent destinations. Debug lives inside About; the experimental
        // switches moved to their own second-level page so this tab stays short.
        AppSection(stringResource(R.string.settings_other)) {
            ArrowPreference(
                title = stringResource(R.string.settings_experimental),
                summary = stringResource(R.string.settings_experimental_subtitle),
                onClick = onNavigateToExperimentalFeatures
            )
            ArrowPreference(
                title = stringResource(R.string.settings_scope_prompts),
                summary = stringResource(R.string.settings_scope_prompts_summary),
                onClick = onNavigateToScopePrompts
            )
            ArrowPreference(
                title = stringResource(R.string.settings_backup_restore),
                summary = stringResource(R.string.settings_backup_restore_summary),
                onClick = onNavigateToBackupRestore
            )
            ArrowPreference(
                title = stringResource(R.string.settings_about),
                summary = stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
                onClick = onNavigateToAbout
            )
        }
    }
}
