package com.takekazex.hypertweak.ui.page.screenshot

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.ScreenshotSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun ScreenshotPage(onBack: () -> Unit) {
    val settingsStore = LocalAppSettingsStore.current
    var enabled by rememberStoredSetting(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME, settingsStore::putBoolean) { settingsStore.getBoolean(ScreenshotSettingsKeys.KEY_SCREENSHOT_AUTO_FRAME, false) }
    var editorMode by rememberStoredSetting(ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE, settingsStore::putInt) { settingsStore.getInt(ScreenshotSettingsKeys.KEY_SCREENSHOT_FRAME_EDITOR_MODE, 0).let { if (it == 1) 1 else 0 } }
    var copyToClipboard by rememberStoredSetting(ScreenshotSettingsKeys.KEY_SCREENSHOT_COPY_TO_CLIPBOARD, settingsStore::putBoolean) { settingsStore.getBoolean(ScreenshotSettingsKeys.KEY_SCREENSHOT_COPY_TO_CLIPBOARD, false) }
    var picturesDirectory by rememberStoredSetting(ScreenshotSettingsKeys.KEY_SCREENSHOT_PICTURES_DIRECTORY, settingsStore::putBoolean) { settingsStore.getBoolean(ScreenshotSettingsKeys.KEY_SCREENSHOT_PICTURES_DIRECTORY, false) }
    AppScrollPage(
        title = stringResource(R.string.screenshot_section),
        onBack = onBack,
        backContentDescription = stringResource(R.string.icon_back),
    ) {
        MissingScopeCard("com.miui.screenshot")
        AppSection {
            Column {
                SwitchPreference(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                    },
                    title = stringResource(R.string.screenshot_auto_frame_title),
                    summary = stringResource(R.string.screenshot_auto_frame_summary)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.screenshot_frame_editor_mode_title),
                    summary = stringResource(R.string.screenshot_frame_editor_mode_summary),
                    items = listOf(
                        stringResource(R.string.screenshot_frame_editor_removable),
                        stringResource(R.string.screenshot_frame_editor_crop)
                    ),
                    selectedIndex = editorMode,
                    onSelectedIndexChange = { mode ->
                        editorMode = mode
                    }
                )
                SwitchPreference(
                    checked = copyToClipboard,
                    onCheckedChange = {
                        copyToClipboard = it
                    },
                    title = stringResource(R.string.screenshot_copy_to_clipboard_title),
                    summary = stringResource(R.string.screenshot_copy_to_clipboard_summary)
                )
                SwitchPreference(
                    checked = picturesDirectory,
                    onCheckedChange = {
                        picturesDirectory = it
                    },
                    title = stringResource(R.string.screenshot_pictures_directory_title),
                    summary = stringResource(R.string.screenshot_pictures_directory_summary)
                )
            }
        }
    }
}
