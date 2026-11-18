package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.util.platform.PlatformLevel
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Experimental switches (Settings → Experimental).
 *
 * Second-level page so the settings tab does not grow into one long scroll. Everything here is
 * still under development, which the header note states outright; the OS4-only entries keep the
 * same platform gates they had when they lived on the settings page.
 */
@Composable
fun ExperimentalFeaturesPage(
    onBack: () -> Unit,
    onNavigateToGlassTuner: () -> Unit,
    onNavigateToControlCenterCorner: () -> Unit,
    onNavigateToControlCenterResize: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    val settingsStore = LocalAppSettingsStore.current

    var unlockVisual by rememberStoredSetting(PlatformSettingsKeys.KEY_UNLOCK_MORE_VISUAL_PERCEPTION, settingsStore::putBoolean) { settingsStore.platform.unlockMoreVisualPerception() }
    var unlockGestures by rememberStoredSetting(PlatformSettingsKeys.KEY_UNLOCK_MORE_AON_GESTURES, settingsStore::putBoolean) { settingsStore.platform.unlockMoreAonGestures() }
    var unlockAdaptiveRefresh by rememberStoredSetting(PlatformSettingsKeys.KEY_UNLOCK_ADAPTIVE_REFRESH_PRO, settingsStore::putBoolean) { settingsStore.platform.unlockAdaptiveRefreshPro() }
    AppScrollPage(
        title = stringResource(R.string.experimental_features_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.experimental_features_back),
    ) {
        MissingScopeCard("com.android.systemui", "com.android.settings", "system")

        AppSection {
            Text(
                text = stringResource(R.string.experimental_features_notes),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        // The material style (材质风格) with its two modes only exists on OS4; OS3 SystemUI has
        // neither the bionics resources nor the material_style key.
        if (PlatformLevel.isOs4) {
            AppSection(title = stringResource(R.string.experimental_features_section)) {
                ArrowPreference(
                    title = stringResource(R.string.settings_glass_material_tuner),
                    summary = stringResource(R.string.settings_glass_material_tuner_summary),
                    onClick = onNavigateToGlassTuner
                )
                // Control Center custom corner radius. The radius lives in the OS4 control
                // center plugin classes, so the entry is OS4-only.
                ArrowPreference(
                    title = stringResource(R.string.tweaks_cc_corner_enabled_title),
                    summary = stringResource(R.string.tweaks_cc_corner_enabled_summary),
                    onClick = onNavigateToControlCenterCorner
                )
                // Control-center editor cards: the fixed main-panel contents (big cards,
                // media player, brightness/volume sliders, device center) show up in
                // 编辑与排序 and become drag-reorderable like the quick actions.
                SwitchPreference(
                    checked = appSettings.ccEditEnabled,
                    onCheckedChange = { appSettings.ccEditEnabled = it },
                    title = stringResource(R.string.settings_cc_edit_title),
                    summary = stringResource(R.string.settings_cc_edit_summary)
                )
                // Control-center element sizes (big cards, sliders, media player, device
                // center) plus quick switches rendered as big cards.
                ArrowPreference(
                    title = stringResource(R.string.cc_resize_title),
                    summary = stringResource(R.string.cc_resize_enabled_summary),
                    onClick = onNavigateToControlCenterResize
                )
            }

        }

        // AON visual-perception / air-gesture unlocks: reveal Settings entries that the device
        // hides behind `config_aon_*` resource gates (see VisualPerceptionSettingsHooker).
        // Shared snapshots update system-server controllers and rebuild cached Settings
        // preference trees with saved state on the next resume.
        AppSection(title = stringResource(R.string.settings_unlock_visual_perception_title)) {
            SwitchPreference(
                checked = unlockVisual,
                onCheckedChange = {
                    unlockVisual = it
                },
                title = stringResource(R.string.settings_unlock_visual_perception_title),
                summary = stringResource(R.string.settings_unlock_visual_perception_summary)
            )
            SwitchPreference(
                checked = unlockGestures,
                onCheckedChange = {
                    unlockGestures = it
                },
                title = stringResource(R.string.settings_unlock_aon_gestures_title),
                summary = stringResource(R.string.settings_unlock_aon_gestures_summary)
            )
            // 自适应刷新率Pro (Mimotion PWM): reveal the 显示与亮度 row that HyperOS removes
            // when `ro.display.enable_pwm_switch` is unset, and let system_server re-apply
            // the saved mode at boot. Settings-side reveal needs a fresh Settings process;
            // the runtime re-apply needs a reboot; extra gears still depend on panel/DF support.
            SwitchPreference(
                checked = unlockAdaptiveRefresh,
                onCheckedChange = {
                    unlockAdaptiveRefresh = it
                },
                title = stringResource(R.string.settings_unlock_adaptive_refresh_title),
                summary = stringResource(R.string.settings_unlock_adaptive_refresh_summary)
            )
        }
    }
}
