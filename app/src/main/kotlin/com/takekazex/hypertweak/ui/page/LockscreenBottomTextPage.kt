package com.takekazex.hypertweak.ui.page

import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.settings.contract.LockscreenBottomTextOptions
import com.takekazex.hypertweak.util.RestartScopeSelection
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
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Settings → System UI → Lockscreen Bottom Text. */
@Composable
fun LockscreenBottomTextPage(onBack: () -> Unit) {
    val services = LocalAppServices.current
    val settingsStore = LocalAppSettingsStore.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current

    var enabled by remember {
        mutableStateOf(settingsStore.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT, false))
    }
    var mode by remember {
        mutableIntStateOf(
            settingsStore.getInt(
                SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT_MODE,
                LockscreenBottomTextOptions.DEFAULT_MODE
            )
        )
    }
    var mask by remember {
        mutableIntStateOf(
            settingsStore.getInt(
                SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT_MASK,
                LockscreenBottomTextOptions.DEFAULT_MASK
            )
        )
    }
    var systemUiRestartPending by rememberSaveable { mutableStateOf(false) }

    fun requestSystemUiRestart() {
        systemUiRestartPending = true
        requestRestartScopes(RestartScopeSelection(systemUi = true))
    }

    fun setMaskBit(bit: Int, checked: Boolean) {
        mask = if (checked) mask or bit else mask and bit.inv()
        settingsStore.putInt(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, mask)
        requestSystemUiRestart()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.bottom_text_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.bottom_text_back))
                }
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

            SmallTitle(stringResource(R.string.bottom_text_section_main))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            enabled = checked
                            settingsStore.putBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT, checked)
                            requestSystemUiRestart()
                        },
                        title = stringResource(R.string.bottom_text_enabled_title),
                        summary = stringResource(R.string.bottom_text_enabled_summary)
                    )
                    if (systemUiRestartPending) {
                        ArrowPreference(
                            title = stringResource(R.string.bottom_text_restart_title),
                            summary = stringResource(R.string.bottom_text_restart_summary),
                            enabled = !restartInProgress(RestartScopeSelection(systemUi = true)),
                            onClick = {
                                settingsStore.flush()
                                services.restarts.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = RestartScopeSelection(systemUi = true),
                                    onCompleted = { completed ->
                                        handleRestartedScopes(completed)
                                        if (completed.systemUi) systemUiRestartPending = false
                                    },
                                )
                            }
                        )
                    }
                    OverlayDropdownPreference(
                        title = stringResource(R.string.bottom_text_mode_title),
                        summary = stringResource(R.string.bottom_text_mode_summary),
                        items = listOf(
                            stringResource(R.string.bottom_text_mode_blacklist),
                            stringResource(R.string.bottom_text_mode_whitelist)
                        ),
                        selectedIndex = mode.coerceIn(
                            LockscreenBottomTextOptions.MODE_BLACKLIST,
                            LockscreenBottomTextOptions.MODE_WHITELIST
                        ),
                        onSelectedIndexChange = { selected ->
                            mode = selected
                            settingsStore.putInt(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT_MODE, selected)
                            requestSystemUiRestart()
                        }
                    )
                }
            }

            SmallTitle(stringResource(R.string.bottom_text_items_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_CHARGING != 0,
                        title = stringResource(R.string.bottom_text_charging_title),
                        summary = stringResource(R.string.bottom_text_charging_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_CHARGING, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_DND != 0,
                        title = stringResource(R.string.bottom_text_dnd_title),
                        summary = stringResource(R.string.bottom_text_dnd_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_DND, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_NOTIFICATION_COUNT != 0,
                        title = stringResource(R.string.bottom_text_notification_title),
                        summary = stringResource(R.string.bottom_text_notification_summary),
                        onCheckedChange = {
                            setMaskBit(LockscreenBottomTextOptions.CATEGORY_NOTIFICATION_COUNT, it)
                        }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_TRUST != 0,
                        title = stringResource(R.string.bottom_text_trust_title),
                        summary = stringResource(R.string.bottom_text_trust_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_TRUST, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_SWIPE != 0,
                        title = stringResource(R.string.bottom_text_swipe_title),
                        summary = stringResource(R.string.bottom_text_swipe_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_SWIPE, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_OWNER_INFO != 0,
                        title = stringResource(R.string.bottom_text_owner_info_title),
                        summary = stringResource(R.string.bottom_text_owner_info_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_OWNER_INFO, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_DISCLOSURE != 0,
                        title = stringResource(R.string.bottom_text_disclosure_title),
                        summary = stringResource(R.string.bottom_text_disclosure_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_DISCLOSURE, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_LOGOUT != 0,
                        title = stringResource(R.string.bottom_text_logout_title),
                        summary = stringResource(R.string.bottom_text_logout_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_LOGOUT, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_ALIGNMENT != 0,
                        title = stringResource(R.string.bottom_text_alignment_title),
                        summary = stringResource(R.string.bottom_text_alignment_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_ALIGNMENT, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_TRANSIENT != 0,
                        title = stringResource(R.string.bottom_text_transient_title),
                        summary = stringResource(R.string.bottom_text_transient_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_TRANSIENT, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_PERSISTENT_UNLOCK != 0,
                        title = stringResource(R.string.bottom_text_persistent_unlock_title),
                        summary = stringResource(R.string.bottom_text_persistent_unlock_summary),
                        onCheckedChange = {
                            setMaskBit(LockscreenBottomTextOptions.CATEGORY_PERSISTENT_UNLOCK, it)
                        }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_USER_LOCKED != 0,
                        title = stringResource(R.string.bottom_text_user_locked_title),
                        summary = stringResource(R.string.bottom_text_user_locked_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_USER_LOCKED, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_BIOMETRIC != 0,
                        title = stringResource(R.string.bottom_text_biometric_title),
                        summary = stringResource(R.string.bottom_text_biometric_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_BIOMETRIC, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_BIOMETRIC_FOLLOW_UP != 0,
                        title = stringResource(R.string.bottom_text_biometric_follow_up_title),
                        summary = stringResource(R.string.bottom_text_biometric_follow_up_summary),
                        onCheckedChange = {
                            setMaskBit(LockscreenBottomTextOptions.CATEGORY_BIOMETRIC_FOLLOW_UP, it)
                        }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_ADAPTIVE_AUTH != 0,
                        title = stringResource(R.string.bottom_text_adaptive_auth_title),
                        summary = stringResource(R.string.bottom_text_adaptive_auth_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_ADAPTIVE_AUTH, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_WATCH_DISCONNECTED != 0,
                        title = stringResource(R.string.bottom_text_watch_disconnected_title),
                        summary = stringResource(R.string.bottom_text_watch_disconnected_summary),
                        onCheckedChange = {
                            setMaskBit(LockscreenBottomTextOptions.CATEGORY_WATCH_DISCONNECTED, it)
                        }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_SECURE_LOCK_DEVICE != 0,
                        title = stringResource(R.string.bottom_text_secure_lock_device_title),
                        summary = stringResource(R.string.bottom_text_secure_lock_device_summary),
                        onCheckedChange = {
                            setMaskBit(LockscreenBottomTextOptions.CATEGORY_SECURE_LOCK_DEVICE, it)
                        }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_CLICK_TO_UNLOCK != 0,
                        title = stringResource(R.string.bottom_text_click_to_unlock_title),
                        summary = stringResource(R.string.bottom_text_click_to_unlock_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_CLICK_TO_UNLOCK, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_KEY_TO_UNLOCK != 0,
                        title = stringResource(R.string.bottom_text_key_to_unlock_title),
                        summary = stringResource(R.string.bottom_text_key_to_unlock_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_KEY_TO_UNLOCK, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_ENTER_TO_UNLOCK != 0,
                        title = stringResource(R.string.bottom_text_enter_to_unlock_title),
                        summary = stringResource(R.string.bottom_text_enter_to_unlock_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_ENTER_TO_UNLOCK, it) }
                    )
                    BottomTextSwitch(
                        checked = mask and LockscreenBottomTextOptions.CATEGORY_UNKNOWN != 0,
                        title = stringResource(R.string.bottom_text_unknown_title),
                        summary = stringResource(R.string.bottom_text_unknown_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextOptions.CATEGORY_UNKNOWN, it) }
                    )
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
private fun BottomTextSwitch(
    checked: Boolean,
    title: String,
    summary: String,
    onCheckedChange: (Boolean) -> Unit
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        summary = summary
    )
}
