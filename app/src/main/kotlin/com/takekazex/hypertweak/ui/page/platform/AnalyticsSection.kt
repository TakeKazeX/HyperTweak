package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.AnalyticsSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.util.platform.AnalyticsPackageControl
import com.takekazex.hypertweak.util.platform.PlatformLevel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun AnalyticsSection(refreshActive: Boolean) {
    if (!PlatformLevel.isOs4) return
    val context = LocalContext.current.applicationContext
    val settings = LocalAppSettingsStore.current
    val scope = rememberCoroutineScope()
    val analytics = LocalAppServices.current.analytics
    val owner = analytics.canManage()
    var packageState by remember { mutableStateOf<AnalyticsPackageControl.State?>(null) }
    var busy by remember { mutableStateOf(false) }
    var blockTelemetry by rememberStoredSetting(AnalyticsSettingsKeys.KEY_ANALYTICS_BLOCK_TELEMETRY, settings::putBoolean) {
        settings.getBoolean(AnalyticsSettingsKeys.KEY_ANALYTICS_BLOCK_TELEMETRY, false)
    }

    fun apply(disable: Boolean? = null) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                packageState = analytics.update(context, disable)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(refreshActive) {
        if (owner && refreshActive) apply()
    }

    val state = packageState
    val hidden = owner && (state == null || state.disabled && !state.pending)
    if (!hidden && blockTelemetry) MissingScopeCard(AnalyticsSettingsKeys.PACKAGE)
    AppSection(title = stringResource(R.string.analytics_title)) {
        if (owner) SwitchPreference(
            checked = state?.disabled == true,
            enabled = !busy && state != null,
            onCheckedChange = { apply(it) },
            title = stringResource(R.string.analytics_disable_title),
            summary = stringResource(when {
                busy -> R.string.analytics_package_applying
                state?.failed == true -> R.string.analytics_package_failed
                else -> R.string.analytics_disable_summary
            }),
        )
        if (!hidden) SwitchPreference(
            checked = blockTelemetry,
            onCheckedChange = { blockTelemetry = it },
            title = stringResource(R.string.analytics_block_title),
            summary = stringResource(R.string.analytics_block_summary),
        )
    }
}
