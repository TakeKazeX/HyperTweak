package com.takekazex.hypertweak.ui.page.systemui

import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.util.scope.ScopeManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Super Island controls, reached from the Features tab. State stays owned by MainActivity. */
@Composable
fun SuperIslandPage(
    onBack: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val services = LocalAppServices.current
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    val scopeRejected = stringResource(R.string.feature_scope_rejected)
    val scopeUnavailable = stringResource(R.string.home_scope_service_unavailable)
    // The full-screen flashlight page runs in the plugin's own process; its exit animation needs
    // that package in scope, so enabling the panel asks LSPosed for it when it is missing.
    val onFlashlightChange: (Boolean) -> Unit = { checked ->
        appSettings.islandFlashlightControl = checked
        if (checked) coroutines.launch {
            val live = services.scopes.currentScope() ?: return@launch
            if (FLASHLIGHT_PAGE_PACKAGE in live) return@launch
            val message = when (val result = services.scopes.request(setOf(FLASHLIGHT_PAGE_PACKAGE))) {
                is ScopeManager.Result.Failed -> result.message
                ScopeManager.Result.ServiceUnavailable -> scopeUnavailable
                is ScopeManager.Result.Rejected -> scopeRejected
                is ScopeManager.Result.Applied, ScopeManager.Result.NoChange -> null
            }
            message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        }
    }
    AppScrollPage(
        title = stringResource(R.string.tweaks_focus_notification_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_system_ui_back),
        scrollState = scrollState,
    ) {
        if (appSettings.islandFlashlightControl) MissingScopeCard("com.android.systemui", "com.xiaomi.xmsf", FLASHLIGHT_PAGE_PACKAGE)
        else MissingScopeCard("com.android.systemui", "com.xiaomi.xmsf")

        AppSection(title = stringResource(R.string.tweaks_focus_notification_title)) {
            SwitchPreference(
                checked = appSettings.threeIslands,
                onCheckedChange = { appSettings.threeIslands = it },
                title = stringResource(R.string.tweaks_three_islands_title),
                summary = stringResource(R.string.tweaks_three_islands_summary)
            )
            SwitchPreference(
                checked = appSettings.landscapeFullIslands,
                onCheckedChange = { appSettings.landscapeFullIslands = it },
                title = stringResource(R.string.tweaks_landscape_full_islands_title),
                summary = stringResource(R.string.tweaks_landscape_full_islands_summary)
            )
            SwitchPreference(
                checked = appSettings.landscapeMoreIslands,
                onCheckedChange = { appSettings.landscapeMoreIslands = it },
                title = stringResource(R.string.tweaks_landscape_more_islands_title),
                summary = stringResource(R.string.tweaks_landscape_more_islands_summary)
            )
            SwitchPreference(
                checked = appSettings.removeIslandPlaceholder,
                onCheckedChange = { appSettings.removeIslandPlaceholder = it },
                title = stringResource(R.string.tweaks_remove_island_placeholder_title),
                summary = stringResource(R.string.tweaks_remove_island_placeholder_summary)
            )
            SwitchPreference(
                checked = appSettings.ccHideSuperIsland,
                onCheckedChange = { appSettings.ccHideSuperIsland = it },
                title = stringResource(R.string.tweaks_cc_hide_super_island_title),
                summary = stringResource(R.string.tweaks_cc_hide_super_island_summary)
            )
            SwitchPreference(
                checked = appSettings.islandLiveUpdateIcon,
                onCheckedChange = { appSettings.islandLiveUpdateIcon = it },
                title = stringResource(R.string.tweaks_island_live_update_icon_title),
                summary = stringResource(R.string.tweaks_island_live_update_icon_summary)
            )
            SwitchPreference(
                checked = appSettings.islandNarrowTimeFont,
                onCheckedChange = { appSettings.islandNarrowTimeFont = it },
                title = stringResource(R.string.tweaks_island_narrow_time_font_title),
                summary = stringResource(R.string.tweaks_island_narrow_time_font_summary)
            )
            SwitchPreference(
                checked = appSettings.islandPullDownStretch,
                onCheckedChange = { appSettings.islandPullDownStretch = it },
                title = stringResource(R.string.tweaks_island_pull_down_stretch_title),
                summary = stringResource(R.string.tweaks_island_pull_down_stretch_summary)
            )
            SwitchPreference(
                checked = appSettings.islandLottieResume,
                onCheckedChange = { appSettings.islandLottieResume = it },
                title = stringResource(R.string.tweaks_island_lottie_resume_title),
                summary = stringResource(R.string.tweaks_island_lottie_resume_summary)
            )
            SwitchPreference(
                checked = appSettings.islandFlashlightControl,
                onCheckedChange = onFlashlightChange,
                title = stringResource(R.string.tweaks_island_flashlight_control_title),
                summary = stringResource(R.string.tweaks_island_flashlight_control_summary)
            )
            SwitchPreference(
                checked = appSettings.focusNotificationUnlockWhitelist,
                onCheckedChange = { appSettings.focusNotificationUnlockWhitelist = it },
                title = stringResource(R.string.tweaks_focus_unlock_whitelist_title),
                summary = stringResource(R.string.tweaks_focus_unlock_whitelist_summary)
            )
            SwitchPreference(
                checked = appSettings.mediaSuperIslandMultiSession,
                onCheckedChange = { appSettings.mediaSuperIslandMultiSession = it },
                title = stringResource(R.string.tweaks_media_super_island_multi_session_title),
                summary = stringResource(R.string.tweaks_media_super_island_multi_session_summary)
            )
            SwitchPreference(
                checked = appSettings.mediaSuperIslandUnlockWhitelist,
                onCheckedChange = { appSettings.mediaSuperIslandUnlockWhitelist = it },
                title = stringResource(R.string.tweaks_media_super_island_unlock_whitelist_title),
                summary = stringResource(R.string.tweaks_media_super_island_unlock_whitelist_summary)
            )
            SwitchPreference(
                checked = appSettings.xmsfUnlockFocusAuth,
                onCheckedChange = { appSettings.xmsfUnlockFocusAuth = it },
                title = stringResource(R.string.tweaks_xmsf_unlock_focus_auth_title),
                summary = stringResource(R.string.tweaks_xmsf_unlock_focus_auth_summary)
            )
        }
    }
}

/** 系统界面组件: hosts the full-screen flashlight page (`miui.systemui.plugin:flashlight`). */
private const val FLASHLIGHT_PAGE_PACKAGE = "miui.systemui.plugin"
