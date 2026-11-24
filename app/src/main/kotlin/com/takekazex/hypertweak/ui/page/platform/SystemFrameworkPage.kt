package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.VolumeKeyStepPolicy
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.rememberStoredSetting
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.dialog.IntValueDialog
import com.takekazex.hypertweak.ui.components.scope.MissingScopeCard
import com.takekazex.hypertweak.ui.components.slider.rememberSliderEdit
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SystemFrameworkPage(
    onBack: () -> Unit,
    onNavigateToFreeform: () -> Unit,
    onNavigateToPackageManager: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    val settingsStore = LocalAppSettingsStore.current
    var forceScreenshot by remember { mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_FORCE_SCREENSHOT, false)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    LaunchedEffect(lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) {
            forceScreenshot = settingsStore.getBoolean(PlatformSettingsKeys.KEY_FORCE_SCREENSHOT, false)
        }
    }
    AppScrollPage(
        title = stringResource(R.string.force_screenshot_section),
        onBack = onBack,
        backContentDescription = stringResource(R.string.aosp_back),
    ) {
        MissingScopeCard("system", "com.android.systemui", "com.android.thememanager")

        AppSection {
            ArrowPreference(
                title = stringResource(R.string.package_manager_title),
                summary = stringResource(R.string.package_manager_summary),
                onClick = onNavigateToPackageManager
            )
            ArrowPreference(
                title = stringResource(R.string.system_freeform_title),
                summary = stringResource(R.string.system_freeform_summary),
                onClick = onNavigateToFreeform
            )
            SwitchPreference(
                checked = forceScreenshot,
                onCheckedChange = {
                    forceScreenshot = it
                    settingsStore.putBoolean(PlatformSettingsKeys.KEY_FORCE_SCREENSHOT, it)
                },
                title = stringResource(R.string.force_screenshot_title),
                summary = stringResource(R.string.force_screenshot_summary)
            )
        }

        AppSection(title = stringResource(R.string.system_framework_appearance)) {
            SwitchPreference(
                checked = appSettings.allowThirdPartyTheme,
                onCheckedChange = { appSettings.allowThirdPartyTheme = it },
                title = stringResource(R.string.tweaks_allow_third_party_theme_title),
                summary = stringResource(R.string.tweaks_allow_third_party_theme_summary)
            )
            SwitchPreference(
                checked = appSettings.unlockThirdPartyDarkMode,
                onCheckedChange = { appSettings.unlockThirdPartyDarkMode = it },
                title = stringResource(R.string.settings_unlock_third_party_dark_mode_title),
                summary = stringResource(R.string.settings_unlock_third_party_dark_mode_summary)
            )
        }
        VolumeKeyStepSection()
    }
}

@Composable
private fun VolumeKeyStepSection() {
    val settingsStore = LocalAppSettingsStore.current
    var volumeFirstPress by rememberSaveable { mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_VOLUME_FIRST_PRESS, false)) }
    var volumeKeyStepEnabled by rememberSaveable { mutableStateOf(settingsStore.getBoolean(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_ENABLED, false)) }
    var volumeKeyStepCount by rememberStoredSetting(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_COUNT, settingsStore::putInt) { settingsStore.getInt(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_COUNT, PlatformSettingsKeys.DEFAULT_VOLUME_KEY_STEP_COUNT).coerceIn(VolumeKeyStepPolicy.MIN_STEPS, VolumeKeyStepPolicy.MAX_STEPS) }
    var volumeKeyStepScope by rememberStoredSetting(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_SCOPE, settingsStore::putInt) { settingsStore.getInt(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_SCOPE, PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY).coerceIn(PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY, PlatformSettingsKeys.VOLUME_KEY_SCOPE_ACTIVE_STREAM) }
    var volumeKeySliderExpanded by remember { mutableStateOf(false) }
    val volumeKeySlider = rememberSliderEdit(volumeKeyStepCount.toFloat())
    val volumeKeySliderValue = volumeKeySlider.value
    val displayedVolumeKeyStepCount = volumeKeySliderValue.roundToInt().coerceIn(
        VolumeKeyStepPolicy.MIN_STEPS,
        VolumeKeyStepPolicy.MAX_STEPS
    )
    fun setVolumeKeyStepCount(value: Int) {
        val resolved = value.coerceIn(VolumeKeyStepPolicy.MIN_STEPS, VolumeKeyStepPolicy.MAX_STEPS)
        volumeKeyStepCount = resolved
    }
    fun setVolumeKeyStepScope(value: Int) {
        val resolved = value.coerceIn(
            PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY,
            PlatformSettingsKeys.VOLUME_KEY_SCOPE_ACTIVE_STREAM
        )
        volumeKeyStepScope = resolved
    }

    AppSection(title = stringResource(R.string.volume_key_section)) {
        SwitchPreference(
            checked = volumeFirstPress,
            onCheckedChange = {
                volumeFirstPress = it
                settingsStore.putBoolean(PlatformSettingsKeys.KEY_VOLUME_FIRST_PRESS, it)
            },
            title = stringResource(R.string.volume_first_press),
            summary = stringResource(R.string.volume_first_press_summary)
        )
        SwitchPreference(
            checked = volumeKeyStepEnabled,
            onCheckedChange = {
                volumeKeyStepEnabled = it
                if (!it) volumeKeySliderExpanded = false
                settingsStore.putBoolean(PlatformSettingsKeys.KEY_VOLUME_KEY_STEP_ENABLED, it)
            },
            title = stringResource(R.string.volume_key_step_enabled),
            summary = stringResource(R.string.volume_key_step_enabled_summary)
        )
        MiuixExpandableContent(visible = volumeKeyStepEnabled) {
            OverlayDropdownPreference(
                title = stringResource(R.string.volume_key_scope_title),
                summary = stringResource(if (volumeKeyStepScope == PlatformSettingsKeys.VOLUME_KEY_SCOPE_MEDIA_ONLY) R.string.volume_key_scope_media else R.string.volume_key_scope_active_stream),
                showValue = false,
                items = listOf(
                    stringResource(R.string.volume_key_scope_media),
                    stringResource(R.string.volume_key_scope_active_stream)
                ),
                selectedIndex = volumeKeyStepScope,
                enabled = volumeKeyStepEnabled,
                onSelectedIndexChange = ::setVolumeKeyStepScope
            )
            ArrowPreference(
                title = stringResource(R.string.volume_key_step_title),
                summary = pluralStringResource(
                    R.plurals.volume_key_step_summary,
                    displayedVolumeKeyStepCount,
                    displayedVolumeKeyStepCount,
                    VolumeKeyStepPolicy.percentStep(displayedVolumeKeyStepCount)
                ),
                endActions = {
                    Text(
                        text = pluralStringResource(
                            R.plurals.volume_key_step_value,
                            displayedVolumeKeyStepCount,
                            displayedVolumeKeyStepCount
                        ),
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                },
                onClick = { volumeKeySliderExpanded = !volumeKeySliderExpanded },
                enabled = volumeKeyStepEnabled,
                holdDownState = volumeKeySliderExpanded,
                bottomAction = {
                    Slider(
                        value = volumeKeySliderValue.coerceIn(
                            VolumeKeyStepPolicy.MIN_STEPS.toFloat(),
                            VolumeKeyStepPolicy.MAX_STEPS.toFloat()
                        ),
                        onValueChange = volumeKeySlider::update,
                        onValueChangeFinished = {
                            volumeKeySlider.finish { it.roundToInt().toFloat() }?.let { setVolumeKeyStepCount(it.toInt()) }
                        },
                        valueRange = VolumeKeyStepPolicy.MIN_STEPS.toFloat()..
                            VolumeKeyStepPolicy.MAX_STEPS.toFloat(),
                        steps = VolumeKeyStepPolicy.MAX_STEPS -
                            VolumeKeyStepPolicy.MIN_STEPS - 1,
                        showKeyPoints = true,
                        enabled = volumeKeyStepEnabled,
                        keyPoints = listOf(5f, 15f, 100f),
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step
                    )
                }
            )
        }
    }
    IntValueDialog(
        show = volumeKeyStepEnabled && volumeKeySliderExpanded,
        title = stringResource(R.string.volume_key_step_title),
        summary = stringResource(R.string.volume_key_step_dialog_summary),
        suffix = stringResource(R.string.volume_key_step_suffix),
        range = VolumeKeyStepPolicy.MIN_STEPS..VolumeKeyStepPolicy.MAX_STEPS,
        currentValue = { volumeKeyStepCount },
        emptyValue = volumeKeyStepCount,
        onValueConfirmed = ::setVolumeKeyStepCount,
        onDismissRequest = { volumeKeySliderExpanded = false }
    )
}
