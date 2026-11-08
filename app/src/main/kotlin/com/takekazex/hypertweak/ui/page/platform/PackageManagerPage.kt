package com.takekazex.hypertweak.ui.page.platform

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PackageManagerSettingsKeys as Keys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.ui.effect.AppScaffold
import com.takekazex.hypertweak.ui.effect.AppTopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun PackageManagerPage(onBack: () -> Unit) {
    val settings = LocalAppSettingsStore.current
    var enabled by remember(settings) {
        mutableStateOf(settings.getBoolean(Keys.KEY_ENABLED, Keys.patchKeys.any { settings.getBoolean(it, false) }))
    }
    val scrollBehavior = MiuixScrollBehavior()
    AppScaffold(topBar = {
        AppTopAppBar(
            title = stringResource(R.string.package_manager_title),
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
            MissingScopeCard("system")

            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.package_manager_master),
                    summary = stringResource(R.string.package_manager_master_summary),
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        settings.putBoolean(Keys.KEY_ENABLED, it)
                    }
                )
            }

            if (enabled) {
                PackageManagerSection(R.string.package_manager_core_section) {
                    PackageManagerSwitch(
                        Keys.KEY_DOWNGRADE, R.string.package_manager_downgrade,
                        R.string.package_manager_downgrade_summary
                    )
                    PackageManagerSwitch(
                        Keys.KEY_UPDATE_SIGNATURE, R.string.package_manager_update_signature,
                        R.string.package_manager_update_signature_summary
                    )
                    PackageManagerSwitch(
                        Keys.KEY_MIN_SIGNATURE_SCHEME, R.string.package_manager_min_scheme,
                        R.string.package_manager_min_scheme_summary
                    )
                    PackageManagerSwitch(
                        Keys.KEY_APK_INTEGRITY, R.string.package_manager_integrity,
                        R.string.package_manager_integrity_summary
                    )
                    PackageManagerSwitch(
                        Keys.KEY_SPLIT_SIGNATURE, R.string.package_manager_split_signature,
                        R.string.package_manager_split_signature_summary
                    )
                    PackageManagerSwitch(
                        Keys.KEY_SHARED_UID, R.string.package_manager_shared_uid,
                        R.string.package_manager_shared_uid_summary
                    )
                }
                PackageManagerSection(R.string.package_manager_advanced_section) {
                    PackageManagerSwitch(
                        Keys.KEY_LOW_TARGET, R.string.package_manager_low_target,
                        R.string.package_manager_low_target_summary
                    )
                    PackageManagerSwitch(
                        Keys.KEY_COMPRESSED_RESOURCES, R.string.package_manager_compressed_resources,
                        R.string.package_manager_compressed_resources_summary
                    )
                    PackageManagerSwitch(
                        Keys.KEY_VERIFICATION_AGENT, R.string.package_manager_verifier,
                        R.string.package_manager_verifier_summary
                    )
                    PackageManagerSwitch(
                        Keys.KEY_PERSISTENT_UPDATE, R.string.package_manager_persistent,
                        R.string.package_manager_persistent_summary
                    )
                    PackageManagerSwitch(
                        Keys.KEY_SYSTEM_APP_UPDATE, R.string.package_manager_system_update,
                        R.string.package_manager_system_update_summary
                    )
                    PackageManagerSwitch(
                        Keys.KEY_ISOLATION, R.string.package_manager_isolation,
                        R.string.package_manager_isolation_summary
                    )
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
private fun PackageManagerSection(@StringRes title: Int, content: @Composable () -> Unit) {
    SmallTitle(stringResource(title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun PackageManagerSwitch(key: String, @StringRes title: Int, @StringRes summary: Int) {
    val settings = LocalAppSettingsStore.current
    var checked by remember(settings, key) { mutableStateOf(settings.getBoolean(key, false)) }
    SwitchPreference(
        title = stringResource(title),
        summary = stringResource(summary),
        checked = checked,
        onCheckedChange = {
            checked = it
            settings.putBoolean(key, it)
        }
    )
}
