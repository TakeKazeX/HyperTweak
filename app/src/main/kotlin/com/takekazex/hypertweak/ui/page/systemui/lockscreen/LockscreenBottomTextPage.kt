package com.takekazex.hypertweak.ui.page.systemui.lockscreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.LockscreenBottomTextOptions
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Settings → System UI → Lockscreen Bottom Text. */
@Composable
fun LockscreenBottomTextPage(onBack: () -> Unit) {
    val settingsStore = LocalAppSettingsStore.current

    var enabled by rememberStoredSetting(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT, settingsStore::putBoolean) { settingsStore.getBoolean(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT, false) }
    var mode by rememberStoredSetting(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT_MODE, settingsStore::putInt) { settingsStore.getInt(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT_MODE, LockscreenBottomTextOptions.DEFAULT_MODE) }
    var mask by rememberStoredSetting(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, settingsStore::putInt) { settingsStore.getInt(SystemUiSettingsKeys.KEY_LOCKSCREEN_BOTTOM_TEXT_MASK, LockscreenBottomTextOptions.DEFAULT_MASK) }

    fun setMaskBit(bit: Int, checked: Boolean) {
        mask = if (checked) mask or bit else mask and bit.inv()

    }

    AppScrollPage(
        title = stringResource(R.string.bottom_text_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.bottom_text_back),
    ) {

        AppSection(stringResource(R.string.bottom_text_section_main)) {
            SwitchPreference(
                checked = enabled,
                onCheckedChange = { checked ->
                    enabled = checked

                },
                title = stringResource(R.string.bottom_text_enabled_title),
                summary = stringResource(R.string.bottom_text_enabled_summary)
            )

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

                }
            )
        }

        AppSection(stringResource(R.string.bottom_text_items_section)) {
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
