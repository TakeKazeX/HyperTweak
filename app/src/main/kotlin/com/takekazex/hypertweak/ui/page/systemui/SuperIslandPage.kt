package com.takekazex.hypertweak.ui.page.systemui

import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
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
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.util.scope.ScopeManager
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Super Island controls, reached from the Features tab. State stays owned by MainActivity. */
@Composable
fun SuperIslandPage(
    onBack: () -> Unit,
    removeIslandPlaceholder: Boolean,
    onRemoveIslandPlaceholderChange: (Boolean) -> Unit,
    threeIslands: Boolean,
    landscapeFullIslands: Boolean,
    landscapeMoreIslands: Boolean,
    ccHideSuperIsland: Boolean,
    islandLiveUpdateIcon: Boolean,
    islandNarrowTimeFont: Boolean,
    islandPullDownStretch: Boolean,
    islandLottieResume: Boolean,
    islandFlashlightControl: Boolean,
    onThreeIslandsChange: (Boolean) -> Unit,
    onLandscapeFullIslandsChange: (Boolean) -> Unit,
    onLandscapeMoreIslandsChange: (Boolean) -> Unit,
    onCcHideSuperIslandChange: (Boolean) -> Unit,
    onIslandLiveUpdateIconChange: (Boolean) -> Unit,
    onIslandNarrowTimeFontChange: (Boolean) -> Unit,
    onIslandPullDownStretchChange: (Boolean) -> Unit,
    onIslandLottieResumeChange: (Boolean) -> Unit,
    onIslandFlashlightControlChange: (Boolean) -> Unit,
    focusNotificationUnlockWhitelist: Boolean,
    onFocusNotificationUnlockWhitelistChange: (Boolean) -> Unit,
    mediaSuperIslandUnlockWhitelist: Boolean,
    onMediaSuperIslandUnlockWhitelistChange: (Boolean) -> Unit,
    xmsfUnlockFocusAuth: Boolean,
    onXmsfUnlockFocusAuthChange: (Boolean) -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val services = LocalAppServices.current
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    val scopeRejected = stringResource(R.string.feature_scope_rejected)
    val scopeUnavailable = stringResource(R.string.home_scope_service_unavailable)
    // The full-screen flashlight page runs in the plugin's own process; its exit animation needs
    // that package in scope, so enabling the panel asks LSPosed for it when it is missing.
    val onFlashlightChange: (Boolean) -> Unit = { checked ->
        onIslandFlashlightControlChange(checked)
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
    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.tweaks_focus_notification_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.settings_system_ui_back))
                }
            }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(scrollState)
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            if (islandFlashlightControl) MissingScopeCard("com.android.systemui", "com.xiaomi.xmsf", FLASHLIGHT_PAGE_PACKAGE)
            else MissingScopeCard("com.android.systemui", "com.xiaomi.xmsf")

            SmallTitle(text = stringResource(R.string.tweaks_focus_notification_title))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = threeIslands,
                        onCheckedChange = onThreeIslandsChange,
                        title = stringResource(R.string.tweaks_three_islands_title),
                        summary = stringResource(R.string.tweaks_three_islands_summary)
                    )
                    SwitchPreference(
                        checked = landscapeFullIslands,
                        onCheckedChange = onLandscapeFullIslandsChange,
                        title = stringResource(R.string.tweaks_landscape_full_islands_title),
                        summary = stringResource(R.string.tweaks_landscape_full_islands_summary)
                    )
                    SwitchPreference(
                        checked = landscapeMoreIslands,
                        onCheckedChange = onLandscapeMoreIslandsChange,
                        title = stringResource(R.string.tweaks_landscape_more_islands_title),
                        summary = stringResource(R.string.tweaks_landscape_more_islands_summary)
                    )
                    SwitchPreference(
                        checked = removeIslandPlaceholder,
                        onCheckedChange = onRemoveIslandPlaceholderChange,
                        title = stringResource(R.string.tweaks_remove_island_placeholder_title),
                        summary = stringResource(R.string.tweaks_remove_island_placeholder_summary)
                    )
                    SwitchPreference(
                        checked = ccHideSuperIsland,
                        onCheckedChange = onCcHideSuperIslandChange,
                        title = stringResource(R.string.tweaks_cc_hide_super_island_title),
                        summary = stringResource(R.string.tweaks_cc_hide_super_island_summary)
                    )
                    SwitchPreference(
                        checked = islandLiveUpdateIcon,
                        onCheckedChange = onIslandLiveUpdateIconChange,
                        title = stringResource(R.string.tweaks_island_live_update_icon_title),
                        summary = stringResource(R.string.tweaks_island_live_update_icon_summary)
                    )
                    SwitchPreference(
                        checked = islandNarrowTimeFont,
                        onCheckedChange = onIslandNarrowTimeFontChange,
                        title = stringResource(R.string.tweaks_island_narrow_time_font_title),
                        summary = stringResource(R.string.tweaks_island_narrow_time_font_summary)
                    )
                    SwitchPreference(
                        checked = islandPullDownStretch,
                        onCheckedChange = onIslandPullDownStretchChange,
                        title = stringResource(R.string.tweaks_island_pull_down_stretch_title),
                        summary = stringResource(R.string.tweaks_island_pull_down_stretch_summary)
                    )
                    SwitchPreference(
                        checked = islandLottieResume,
                        onCheckedChange = onIslandLottieResumeChange,
                        title = stringResource(R.string.tweaks_island_lottie_resume_title),
                        summary = stringResource(R.string.tweaks_island_lottie_resume_summary)
                    )
                    SwitchPreference(
                        checked = islandFlashlightControl,
                        onCheckedChange = onFlashlightChange,
                        title = stringResource(R.string.tweaks_island_flashlight_control_title),
                        summary = stringResource(R.string.tweaks_island_flashlight_control_summary)
                    )
                    SwitchPreference(
                        checked = focusNotificationUnlockWhitelist,
                        onCheckedChange = onFocusNotificationUnlockWhitelistChange,
                        title = stringResource(R.string.tweaks_focus_unlock_whitelist_title),
                        summary = stringResource(R.string.tweaks_focus_unlock_whitelist_summary)
                    )
                    SwitchPreference(
                        checked = mediaSuperIslandUnlockWhitelist,
                        onCheckedChange = onMediaSuperIslandUnlockWhitelistChange,
                        title = stringResource(R.string.tweaks_media_super_island_unlock_whitelist_title),
                        summary = stringResource(R.string.tweaks_media_super_island_unlock_whitelist_summary)
                    )
                    SwitchPreference(
                        checked = xmsfUnlockFocusAuth,
                        onCheckedChange = onXmsfUnlockFocusAuthChange,
                        title = stringResource(R.string.tweaks_xmsf_unlock_focus_auth_title),
                        summary = stringResource(R.string.tweaks_xmsf_unlock_focus_auth_summary)
                    )
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

/** 系统界面组件: hosts the full-screen flashlight page (`miui.systemui.plugin:flashlight`). */
private const val FLASHLIGHT_PAGE_PACKAGE = "miui.systemui.plugin"
