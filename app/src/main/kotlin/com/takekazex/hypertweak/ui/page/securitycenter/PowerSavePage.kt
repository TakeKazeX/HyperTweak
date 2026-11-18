package com.takekazex.hypertweak.ui.page.securitycenter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
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

/** Power-save overrides and battery automation. */
@Composable
fun PowerSavePage(onBack: () -> Unit) {
    val settingsStore = LocalAppSettingsStore.current

    var refreshRate by remember { mutableStateOf(settingsStore.powerSave.keepRefreshRateInPowerSave()) }
    var hapticFeedback by remember { mutableStateOf(settingsStore.powerSave.keepHapticFeedbackInPowerSave()) }
    var systemSounds by remember { mutableStateOf(settingsStore.powerSave.keepSystemSoundsInPowerSave()) }
    var wakeupGestures by remember { mutableStateOf(settingsStore.powerSave.keepWakeupGesturesInPowerSave()) }
    var autoPowerSave by remember { mutableStateOf(settingsStore.powerSave.batteryAutoPowerSaveEnabled()) }
    var autoThreshold by remember { mutableFloatStateOf(settingsStore.powerSave.batteryAutoPowerSaveThreshold().toFloat()) }
    var exitWhenCharging by remember { mutableStateOf(settingsStore.powerSave.exitPowerSaveWhenCharging()) }

    fun setOverride(key: String, value: Boolean, powerSaveFeature: Boolean) {
        if (powerSaveFeature) settingsStore.powerSave.setPowerSaveFeature(key, value)
        else settingsStore.putBoolean(key, value)
    }

    AppScrollPage(
        title = stringResource(R.string.power_save_override_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.power_save_override_back),
    ) {
        MissingScopeCard("com.miui.securitycenter", "com.android.systemui")

        AppSection(title = stringResource(R.string.power_save_override_section_features)) {
            SwitchPreference(
                checked = refreshRate,
                onCheckedChange = { checked ->
                    refreshRate = checked
                    setOverride(
                        PlatformSettingsKeys.KEY_KEEP_REFRESH_RATE_IN_POWER_SAVE,
                        checked,
                        true,
                    )
                },
                title = stringResource(R.string.power_save_override_refresh_rate_title),
                summary = stringResource(R.string.power_save_override_refresh_rate_summary)
            )
            SwitchPreference(
                checked = hapticFeedback,
                onCheckedChange = { checked ->
                    hapticFeedback = checked
                    setOverride(
                        PlatformSettingsKeys.KEY_KEEP_HAPTIC_FEEDBACK_IN_POWER_SAVE,
                        checked,
                        true,
                    )
                },
                title = stringResource(R.string.power_save_override_haptic_title),
                summary = stringResource(R.string.power_save_override_haptic_summary)
            )
            SwitchPreference(
                checked = systemSounds,
                onCheckedChange = { checked ->
                    systemSounds = checked
                    setOverride(
                        PlatformSettingsKeys.KEY_KEEP_SYSTEM_SOUNDS_IN_POWER_SAVE,
                        checked,
                        true,
                    )
                },
                title = stringResource(R.string.power_save_override_sounds_title),
                summary = stringResource(R.string.power_save_override_sounds_summary)
            )
            SwitchPreference(
                checked = wakeupGestures,
                onCheckedChange = { checked ->
                    wakeupGestures = checked
                    setOverride(
                        PlatformSettingsKeys.KEY_KEEP_WAKEUP_GESTURES_IN_POWER_SAVE,
                        checked,
                        true,
                    )
                },
                title = stringResource(R.string.power_save_override_wakeup_title),
                summary = stringResource(R.string.power_save_override_wakeup_summary)
            )
        }

        AppSection(title = stringResource(R.string.power_save_override_section_auto)) {
            SwitchPreference(
                checked = autoPowerSave,
                onCheckedChange = { checked ->
                    autoPowerSave = checked
                    setOverride(
                        PlatformSettingsKeys.KEY_BATTERY_AUTO_POWER_SAVE_ENABLED,
                        checked,
                        false,
                    )
                },
                title = stringResource(R.string.power_save_auto_title),
                summary = stringResource(R.string.power_save_auto_summary)
            )
            var thresholdExpanded by rememberSaveable { mutableStateOf(false) }
            ThresholdRow(
                value = autoThreshold,
                expanded = thresholdExpanded,
                onExpandedChange = { thresholdExpanded = it },
                onValueChange = { value ->
                    autoThreshold = value
                },
                onValueSettled = {
                    settingsStore.putInt(
                        PlatformSettingsKeys.KEY_BATTERY_AUTO_POWER_SAVE_THRESHOLD,
                        autoThreshold.roundToInt(),
                    )
                },
            )
            SwitchPreference(
                checked = exitWhenCharging,
                onCheckedChange = { checked ->
                    exitWhenCharging = checked
                    setOverride(
                        PlatformSettingsKeys.KEY_EXIT_POWER_SAVE_WHEN_CHARGING,
                        checked,
                        false,
                    )
                },
                title = stringResource(R.string.power_save_exit_charging_title),
                summary = stringResource(R.string.power_save_exit_charging_summary)
            )
        }
    }
}

/**
 * The low-battery threshold row: a tap expands the inline slider and opens the numeric
 * [IntValueDialog] at the same time, mirroring the interface-scale and corner-radius rows. The
 * slider alone cannot reach an exact value comfortably on a narrow screen, and the dialog alone
 * would not show the current value.
 */
@Composable
private fun ThresholdRow(
    value: Float,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onValueChange: (Float) -> Unit,
    onValueSettled: () -> Unit,
) {
    val slider = rememberSliderEdit(value)
    val range = PlatformSettingsKeys.MIN_BATTERY_AUTO_POWER_SAVE_THRESHOLD..
        PlatformSettingsKeys.MAX_BATTERY_AUTO_POWER_SAVE_THRESHOLD
    val current = { slider.value.roundToInt().coerceIn(range.first, range.last) }
    ArrowPreference(
        title = stringResource(R.string.power_save_auto_threshold_title),
        summary = stringResource(R.string.power_save_auto_threshold_summary),
        endActions = {
            Text(
                text = stringResource(R.string.power_save_auto_threshold_value, current()),
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        },
        onClick = { onExpandedChange(!expanded) },
        holdDownState = expanded,
        bottomAction = {
            Slider(
                value = slider.value.coerceIn(range.first.toFloat(), range.last.toFloat()),
                onValueChange = slider::update,
                onValueChangeFinished = {
                    slider.finish { it.roundToInt().toFloat() }?.let {
                        onValueChange(it)
                        onValueSettled()
                    }
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = range.last - range.first - 1,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            )
        }
    )
    IntValueDialog(
        show = expanded,
        title = stringResource(R.string.power_save_auto_threshold_title),
        summary = stringResource(R.string.power_save_auto_threshold_dialog_summary),
        suffix = stringResource(R.string.power_save_auto_threshold_suffix),
        range = range,
        currentValue = current,
        // A blank field keeps the current threshold instead of jumping to the minimum.
        emptyValue = current(),
        onValueConfirmed = {
            onValueChange(it.toFloat())
            onValueSettled()
        },
        onDismissRequest = { onExpandedChange(false) },
    )
}
