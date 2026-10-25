package com.takekazex.hypertweak.ui.page

import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun NativePickerPreferences() {
    var photo by remember { mutableStateOf(Preferences.nativePickerEnabled(PlatformSettingsKeys.KEY_NATIVE_PHOTO_PICKER)) }
    var file by remember { mutableStateOf(Preferences.nativePickerEnabled(PlatformSettingsKeys.KEY_NATIVE_FILE_PICKER)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    LaunchedEffect(lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) {
            photo = Preferences.nativePickerEnabled(PlatformSettingsKeys.KEY_NATIVE_PHOTO_PICKER)
            file = Preferences.nativePickerEnabled(PlatformSettingsKeys.KEY_NATIVE_FILE_PICKER)
        }
    }
    SwitchPreference(
        checked = photo,
        onCheckedChange = {
            photo = it
            Preferences.putBoolean(PlatformSettingsKeys.KEY_NATIVE_PHOTO_PICKER, it)
        },
        title = stringResource(R.string.native_photo_picker_title),
        summary = stringResource(R.string.native_photo_picker_summary)
    )
    SwitchPreference(
        checked = file,
        onCheckedChange = {
            file = it
            Preferences.putBoolean(PlatformSettingsKeys.KEY_NATIVE_FILE_PICKER, it)
        },
        title = stringResource(R.string.native_file_picker_title),
        summary = stringResource(R.string.native_file_picker_summary)
    )
}
