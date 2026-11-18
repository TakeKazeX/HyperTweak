package com.takekazex.hypertweak.ui.page.platform

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.FreeformSettingsKeys as Keys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun FreeformPage(onBack: () -> Unit) {
    val jump = rememberFreeformSetting(Keys.KEY_JUMP)
    AppScrollPage(
        title = stringResource(R.string.system_freeform_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.aosp_back),
    ) {
        MissingScopeCard("system", "com.android.systemui")
        AppSection(title = stringResource(R.string.system_freeform_window_section)) {
            FreeformSwitch(Keys.KEY_COUNT, R.string.system_freeform_count_title, R.string.system_freeform_count_summary)
            FreeformSwitch(Keys.KEY_FORCE_ALLOW, R.string.system_freeform_force_title, R.string.system_freeform_force_summary)
            FreeformSwitch(Keys.KEY_FOREGROUND_PIN, R.string.system_freeform_pin_title, R.string.system_freeform_pin_summary)
        }
        AppSection(title = stringResource(R.string.system_freeform_jump_section)) {
            FreeformSwitch(
                Keys.KEY_JUMP, R.string.system_freeform_jump_title, R.string.system_freeform_jump_summary,
                state = jump,
            )
            MiuixExpandableContent(visible = jump.value) {
                FreeformSwitch(
                    Keys.KEY_JUMP_NOTIFICATION, R.string.system_freeform_notification_title,
                    R.string.system_freeform_notification_summary, enabled = jump.value,
                )
                FreeformSwitch(
                    Keys.KEY_JUMP_SHARE, R.string.system_freeform_share_title,
                    R.string.system_freeform_share_summary, enabled = jump.value,
                )
                FreeformSwitch(
                    Keys.KEY_JUMP_CONTENT_LINKS, R.string.system_freeform_content_links_title,
                    R.string.system_freeform_content_links_summary, enabled = jump.value,
                )
                FreeformSwitch(
                    Keys.KEY_COPY_DIRECT_COMMAND, R.string.system_freeform_copy_direct_command_title,
                    R.string.system_freeform_copy_direct_command_summary, enabled = jump.value,
                )
                FreeformSwitch(
                    Keys.KEY_COPY_DIRECT_LOCATION, R.string.system_freeform_copy_direct_location_title,
                    R.string.system_freeform_copy_direct_location_summary, enabled = jump.value,
                )
                FreeformSwitch(
                    Keys.KEY_COPY_DIRECT_TRACKING, R.string.system_freeform_copy_direct_tracking_title,
                    R.string.system_freeform_copy_direct_tracking_summary, enabled = jump.value,
                )
            }
        }
    }
}

@Composable
private fun rememberFreeformSetting(key: String): MutableState<Boolean> {
    val settings = LocalAppSettingsStore.current
    val state = remember(settings, key) { mutableStateOf(settings.getBoolean(key, false)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    LaunchedEffect(settings, key, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) state.value = settings.getBoolean(key, false)
    }
    return state
}

@Composable
private fun FreeformSwitch(
    key: String,
    @StringRes title: Int,
    @StringRes summary: Int,
    state: MutableState<Boolean> = rememberFreeformSetting(key),
    enabled: Boolean = true,
) {
    val settings = LocalAppSettingsStore.current
    SwitchPreference(
        title = stringResource(title),
        summary = stringResource(summary),
        checked = state.value,
        enabled = enabled,
        onCheckedChange = {
            state.value = it
            settings.putBoolean(key, it)
        },
    )
}
