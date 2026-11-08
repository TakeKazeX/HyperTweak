package com.takekazex.hypertweak.ui.page.platform

import androidx.annotation.StringRes
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.FreeformSettingsKeys as Keys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.ui.effect.AppScaffold
import com.takekazex.hypertweak.ui.effect.AppTopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun FreeformPage(onBack: () -> Unit) {
    val jump = rememberFreeformSetting(Keys.KEY_JUMP)
    val scrollBehavior = MiuixScrollBehavior()
    AppScaffold(topBar = {
        AppTopAppBar(
            title = stringResource(R.string.system_freeform_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.aosp_back)) }
            }
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
            MissingScopeCard("system", "com.android.systemui")
            SmallTitle(stringResource(R.string.system_freeform_window_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                FreeformSwitch(Keys.KEY_COUNT, R.string.system_freeform_count_title, R.string.system_freeform_count_summary)
                FreeformSwitch(Keys.KEY_FORCE_ALLOW, R.string.system_freeform_force_title, R.string.system_freeform_force_summary)
                FreeformSwitch(Keys.KEY_FOREGROUND_PIN, R.string.system_freeform_pin_title, R.string.system_freeform_pin_summary)
            }
            SmallTitle(stringResource(R.string.system_freeform_jump_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
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
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
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
