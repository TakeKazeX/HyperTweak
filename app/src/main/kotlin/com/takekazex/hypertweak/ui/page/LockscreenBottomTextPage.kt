package com.takekazex.hypertweak.ui.page

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
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenBottomTextHooker
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.RestartUtils
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Settings → System UI → Lockscreen Bottom Text. */
@Composable
fun LockscreenBottomTextPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current

    var enabled by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT, false))
    }
    var mode by remember {
        mutableIntStateOf(
            Preferences.getInt(
                Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT_MODE,
                LockscreenBottomTextHooker.DEFAULT_MODE
            )
        )
    }
    var mask by remember {
        mutableIntStateOf(
            Preferences.getInt(
                Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT_MASK,
                LockscreenBottomTextHooker.DEFAULT_MASK
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
        Preferences.putInt(Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, mask)
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
                            Preferences.putBoolean(Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT, checked)
                            requestSystemUiRestart()
                        },
                        title = stringResource(R.string.bottom_text_enabled_title),
                        summary = stringResource(R.string.bottom_text_enabled_summary)
                    )
                    if (systemUiRestartPending) {
                        ArrowPreference(
                            title = stringResource(R.string.bottom_text_restart_title),
                            summary = stringResource(R.string.bottom_text_restart_summary),
                            onClick = {
                                Preferences.flush()
                                RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = RestartScopeSelection(systemUi = true)
                                )
                                handleRestartedScopes(RestartScopeSelection(systemUi = true))
                                systemUiRestartPending = false
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
                            LockscreenBottomTextHooker.MODE_BLACKLIST,
                            LockscreenBottomTextHooker.MODE_WHITELIST
                        ),
                        onSelectedIndexChange = { selected ->
                            mode = selected
                            Preferences.putInt(Preferences.KEY_LOCKSCREEN_BOTTOM_TEXT_MODE, selected)
                            requestSystemUiRestart()
                        }
                    )
                }
            }

            SmallTitle(stringResource(R.string.bottom_text_items_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_CHARGING != 0,
                        title = stringResource(R.string.bottom_text_charging_title),
                        summary = stringResource(R.string.bottom_text_charging_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_CHARGING, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_DND != 0,
                        title = stringResource(R.string.bottom_text_dnd_title),
                        summary = stringResource(R.string.bottom_text_dnd_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_DND, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_NOTIFICATION_COUNT != 0,
                        title = stringResource(R.string.bottom_text_notification_title),
                        summary = stringResource(R.string.bottom_text_notification_summary),
                        onCheckedChange = {
                            setMaskBit(LockscreenBottomTextHooker.CATEGORY_NOTIFICATION_COUNT, it)
                        }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_TRUST != 0,
                        title = stringResource(R.string.bottom_text_trust_title),
                        summary = stringResource(R.string.bottom_text_trust_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_TRUST, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_SWIPE != 0,
                        title = stringResource(R.string.bottom_text_swipe_title),
                        summary = stringResource(R.string.bottom_text_swipe_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_SWIPE, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_OWNER_INFO != 0,
                        title = stringResource(R.string.bottom_text_owner_info_title),
                        summary = stringResource(R.string.bottom_text_owner_info_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_OWNER_INFO, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_DISCLOSURE != 0,
                        title = stringResource(R.string.bottom_text_disclosure_title),
                        summary = stringResource(R.string.bottom_text_disclosure_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_DISCLOSURE, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_LOGOUT != 0,
                        title = stringResource(R.string.bottom_text_logout_title),
                        summary = stringResource(R.string.bottom_text_logout_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_LOGOUT, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_ALIGNMENT != 0,
                        title = stringResource(R.string.bottom_text_alignment_title),
                        summary = stringResource(R.string.bottom_text_alignment_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_ALIGNMENT, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_TRANSIENT != 0,
                        title = stringResource(R.string.bottom_text_transient_title),
                        summary = stringResource(R.string.bottom_text_transient_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_TRANSIENT, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_PERSISTENT_UNLOCK != 0,
                        title = stringResource(R.string.bottom_text_persistent_unlock_title),
                        summary = stringResource(R.string.bottom_text_persistent_unlock_summary),
                        onCheckedChange = {
                            setMaskBit(LockscreenBottomTextHooker.CATEGORY_PERSISTENT_UNLOCK, it)
                        }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_USER_LOCKED != 0,
                        title = stringResource(R.string.bottom_text_user_locked_title),
                        summary = stringResource(R.string.bottom_text_user_locked_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_USER_LOCKED, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_BIOMETRIC != 0,
                        title = stringResource(R.string.bottom_text_biometric_title),
                        summary = stringResource(R.string.bottom_text_biometric_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_BIOMETRIC, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_BIOMETRIC_FOLLOW_UP != 0,
                        title = stringResource(R.string.bottom_text_biometric_follow_up_title),
                        summary = stringResource(R.string.bottom_text_biometric_follow_up_summary),
                        onCheckedChange = {
                            setMaskBit(LockscreenBottomTextHooker.CATEGORY_BIOMETRIC_FOLLOW_UP, it)
                        }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_ADAPTIVE_AUTH != 0,
                        title = stringResource(R.string.bottom_text_adaptive_auth_title),
                        summary = stringResource(R.string.bottom_text_adaptive_auth_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_ADAPTIVE_AUTH, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_WATCH_DISCONNECTED != 0,
                        title = stringResource(R.string.bottom_text_watch_disconnected_title),
                        summary = stringResource(R.string.bottom_text_watch_disconnected_summary),
                        onCheckedChange = {
                            setMaskBit(LockscreenBottomTextHooker.CATEGORY_WATCH_DISCONNECTED, it)
                        }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_SECURE_LOCK_DEVICE != 0,
                        title = stringResource(R.string.bottom_text_secure_lock_device_title),
                        summary = stringResource(R.string.bottom_text_secure_lock_device_summary),
                        onCheckedChange = {
                            setMaskBit(LockscreenBottomTextHooker.CATEGORY_SECURE_LOCK_DEVICE, it)
                        }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_CLICK_TO_UNLOCK != 0,
                        title = stringResource(R.string.bottom_text_click_to_unlock_title),
                        summary = stringResource(R.string.bottom_text_click_to_unlock_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_CLICK_TO_UNLOCK, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_KEY_TO_UNLOCK != 0,
                        title = stringResource(R.string.bottom_text_key_to_unlock_title),
                        summary = stringResource(R.string.bottom_text_key_to_unlock_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_KEY_TO_UNLOCK, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_ENTER_TO_UNLOCK != 0,
                        title = stringResource(R.string.bottom_text_enter_to_unlock_title),
                        summary = stringResource(R.string.bottom_text_enter_to_unlock_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_ENTER_TO_UNLOCK, it) }
                    )
                    bottomTextSwitch(
                        checked = mask and LockscreenBottomTextHooker.CATEGORY_UNKNOWN != 0,
                        title = stringResource(R.string.bottom_text_unknown_title),
                        summary = stringResource(R.string.bottom_text_unknown_summary),
                        onCheckedChange = { setMaskBit(LockscreenBottomTextHooker.CATEGORY_UNKNOWN, it) }
                    )
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
private fun bottomTextSwitch(
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
