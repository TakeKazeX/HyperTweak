package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.PlatformLevel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Experimental switches (Settings → Experimental).
 *
 * Second-level page so the settings tab does not grow into one long scroll. Everything here is
 * still under development, which the header note states outright; the OS4-only entries keep the
 * same platform gates they had when they lived on the settings page.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun ExperimentalFeaturesPage(
    onBack: () -> Unit,
    onNavigateToGlassTuner: () -> Unit,
    onNavigateToControlCenterCorner: () -> Unit,
    onNavigateToControlCenterResize: () -> Unit,
    ccEditEnabled: Boolean,
    onCcEditEnabledChange: (Boolean) -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()

    // These switches are read live by the hooks, so the page owns its state directly instead of
    // threading another callback through the navigation layer for each one.
    var unlockVisual by remember { mutableStateOf(Preferences.unlockMoreVisualPerception()) }
    var unlockGestures by remember { mutableStateOf(Preferences.unlockMoreAonGestures()) }
    var unlockAdaptiveRefresh by remember { mutableStateOf(Preferences.unlockAdaptiveRefreshPro()) }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.experimental_features_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.experimental_features_back))
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

            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(
                    text = stringResource(R.string.experimental_features_notes),
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            // The material style (材质风格) with its two modes only exists on OS4; OS3 SystemUI has
            // neither the bionics resources nor the material_style key.
            if (PlatformLevel.isOs4) {
                SmallTitle(stringResource(R.string.experimental_features_section))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
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
                            checked = ccEditEnabled,
                            onCheckedChange = onCcEditEnabledChange,
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
            }

            // AON visual-perception / air-gesture unlocks: reveal Settings entries that the device
            // hides behind `config_aon_*` resource gates (see VisualPerceptionSettingsHooker).
            // These act on the next Settings UI refresh in the same process; the toggles force the
            // Settings-side capability checks only — runtime sensor gates in system_server are
            // separate (see docs/FEATURE_DETAIL.md).
            SmallTitle(stringResource(R.string.settings_unlock_visual_perception_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = unlockVisual,
                        onCheckedChange = {
                            unlockVisual = it
                            Preferences.putBoolean(Preferences.KEY_UNLOCK_MORE_VISUAL_PERCEPTION, it)
                        },
                        title = stringResource(R.string.settings_unlock_visual_perception_title),
                        summary = stringResource(R.string.settings_unlock_visual_perception_summary)
                    )
                    SwitchPreference(
                        checked = unlockGestures,
                        onCheckedChange = {
                            unlockGestures = it
                            Preferences.putBoolean(Preferences.KEY_UNLOCK_MORE_AON_GESTURES, it)
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
                            Preferences.putBoolean(Preferences.KEY_UNLOCK_ADAPTIVE_REFRESH_PRO, it)
                        },
                        title = stringResource(R.string.settings_unlock_adaptive_refresh_title),
                        summary = stringResource(R.string.settings_unlock_adaptive_refresh_summary)
                    )
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
