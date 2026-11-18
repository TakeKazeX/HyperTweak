package com.takekazex.hypertweak.ui.page.platform

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PackageManagerSettingsKeys as Keys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.chrome.AppTextButton as TextButton
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.dialog.AppWindowDialog
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState

@Composable
internal fun PackageManagerPage(onBack: () -> Unit) {
    val settings = LocalAppSettingsStore.current
    fun legacyAuthCreak(): Boolean = Keys.legacyAuthCreakKeys.any { settings.getBoolean(it, false) }
    var enabled by remember(settings) {
        mutableStateOf(
            settings.getBoolean(Keys.KEY_ENABLED, Keys.corePatchKeys.any { settings.getBoolean(it, false) } || legacyAuthCreak())
        )
    }
    var authCreak by remember(settings) {
        mutableStateOf(settings.getBoolean(Keys.KEY_AUTH_CREAK, legacyAuthCreak()))
    }
    var sharedUid by remember(settings) {
        mutableStateOf(settings.getBoolean(Keys.KEY_SHARED_UID, false))
    }
    var systemSharedUid by remember(settings) {
        mutableStateOf(settings.getBoolean(Keys.KEY_SYSTEM_SHARED_UID, false))
    }
    var confirmSystemSharedUid by rememberSaveable { mutableStateOf(false) }
    // Clear an inactive child left by an older build before the parent can be enabled again.
    LaunchedEffect(settings, sharedUid) {
        if (!sharedUid && systemSharedUid) {
            systemSharedUid = false
            confirmSystemSharedUid = false
            settings.putBoolean(Keys.KEY_SYSTEM_SHARED_UID, false)
        }
    }
    AppScrollPage(
        title = stringResource(R.string.package_manager_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.aosp_back),
    ) {
        MissingScopeCard("system")

        AppSection {
            SwitchPreference(
                title = stringResource(R.string.package_manager_master),
                summary = stringResource(R.string.package_manager_master_summary),
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    if (!it) confirmSystemSharedUid = false
                    settings.putBoolean(Keys.KEY_ENABLED, it)
                }
            )
        }

        MiuixExpandableContent(visible = enabled) {
            PackageManagerSection(R.string.package_manager_core_section) {
                PackageManagerSwitch(
                    Keys.KEY_DOWNGRADE, R.string.package_manager_downgrade,
                    R.string.package_manager_downgrade_summary, enabled
                )
                PackageManagerSwitch(
                    Keys.KEY_UPDATE_SIGNATURE, R.string.package_manager_update_signature,
                    R.string.package_manager_update_signature_summary, enabled
                )
                PackageManagerSwitch(
                    Keys.KEY_AUTH_CREAK, R.string.package_manager_auth_creak,
                    R.string.package_manager_auth_creak_summary, enabled,
                    checked = authCreak,
                    onCheckedChange = { checked -> authCreak = checked }
                )
                MiuixExpandableContent(visible = !authCreak) {
                    PackageManagerSwitch(
                        Keys.KEY_DISABLE_INTEGRITY, R.string.package_manager_disable_integrity,
                        R.string.package_manager_disable_integrity_summary, enabled
                    )
                }
                PackageManagerSwitch(
                    Keys.KEY_SPLIT_SIGNATURE, R.string.package_manager_split_signature,
                    R.string.package_manager_split_signature_summary, enabled
                )
                PackageManagerSwitch(
                    Keys.KEY_SHARED_UID, R.string.package_manager_shared_uid,
                    R.string.package_manager_shared_uid_summary, enabled,
                    checked = sharedUid,
                    onCheckedChange = {
                        sharedUid = it
                        if (!it) {
                            confirmSystemSharedUid = false
                            systemSharedUid = false
                            settings.putBoolean(Keys.KEY_SYSTEM_SHARED_UID, false)
                        }
                    }
                )
                MiuixExpandableContent(visible = sharedUid) {
                    SwitchPreference(
                        title = stringResource(R.string.package_manager_system_shared_uid),
                        summary = stringResource(R.string.package_manager_system_shared_uid_summary),
                        checked = systemSharedUid,
                        enabled = enabled && sharedUid,
                        onCheckedChange = { checked ->
                            if (checked) {
                                confirmSystemSharedUid = true
                            } else {
                                systemSharedUid = false
                                settings.putBoolean(Keys.KEY_SYSTEM_SHARED_UID, false)
                            }
                        }
                    )
                }
            }
        }
        PackageManagerSection(R.string.package_manager_advanced_section) {
            PackageManagerSwitch(
                Keys.KEY_LOW_TARGET, R.string.package_manager_low_target,
                R.string.package_manager_low_target_summary, enabled = true
            )
            PackageManagerSwitch(
                Keys.KEY_VERIFICATION_AGENT, R.string.package_manager_verifier,
                R.string.package_manager_verifier_summary, enabled = true
            )
            PackageManagerSwitch(
                Keys.KEY_PERSISTENT_UPDATE, R.string.package_manager_persistent,
                R.string.package_manager_persistent_summary, enabled = true
            )
            PackageManagerSwitch(
                Keys.KEY_SYSTEM_APP_UPDATE, R.string.package_manager_system_update,
                R.string.package_manager_system_update_summary, enabled = true
            )
            PackageManagerSwitch(
                Keys.KEY_ISOLATION, R.string.package_manager_isolation,
                R.string.package_manager_isolation_summary, enabled = true
            )
        }
    }
    AppWindowDialog(
        show = confirmSystemSharedUid && enabled && sharedUid,
        title = stringResource(R.string.package_manager_system_shared_uid_confirm_title),
        onDismissRequest = { confirmSystemSharedUid = false }
    ) {
        val dismiss = LocalDismissState.current
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.package_manager_system_shared_uid_confirm_message))
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(
                    text = stringResource(R.string.settings_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { dismiss?.invoke() ?: run { confirmSystemSharedUid = false } }
                )
                TextButton(
                    text = stringResource(R.string.package_manager_system_shared_uid_confirm),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        if (enabled && sharedUid) {
                            systemSharedUid = true
                            settings.putBoolean(Keys.KEY_SYSTEM_SHARED_UID, true)
                        }
                        dismiss?.invoke() ?: run { confirmSystemSharedUid = false }
                    }
                )
            }
        }
    }
}

@Composable
private fun PackageManagerSection(@StringRes title: Int, content: @Composable () -> Unit) {
    AppSection(stringResource(title)) { content()
    }
}

@Composable
private fun PackageManagerSwitch(
    key: String,
    @StringRes title: Int,
    @StringRes summary: Int,
    enabled: Boolean,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    val settings = LocalAppSettingsStore.current
    var stored by remember(settings, key) { mutableStateOf(settings.getBoolean(key, false)) }
    SwitchPreference(
        title = stringResource(title),
        summary = stringResource(summary),
        checked = checked ?: stored,
        enabled = enabled,
        onCheckedChange = {
            stored = it
            settings.putBoolean(key, it)
            onCheckedChange?.invoke(it)
        }
    )
}
