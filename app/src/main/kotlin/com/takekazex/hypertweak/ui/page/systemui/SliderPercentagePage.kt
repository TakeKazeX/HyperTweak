package com.takekazex.hypertweak.ui.page.systemui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.SliderPercentageTypography
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.slider.SliderEditState
import com.takekazex.hypertweak.ui.components.slider.rememberSliderEdit
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Brightness/volume slider percentages: where they show, their colours, popup capsule and typography. */
@Composable
fun SliderPercentagePage(
    onBack: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    val settingsStore = LocalAppSettingsStore.current
    var typography by remember { mutableStateOf(SliderPercentageTypography.read(settingsStore::getBoolean, settingsStore::getInt)) }
    fun update(key: String, value: Any, next: SliderPercentageTypography) {
        typography = next.safe()
        when (value) {
            is Boolean -> settingsStore.putBoolean(key, value)
            is Int -> settingsStore.putInt(key, value)
        }
    }
    // Hoisted so the preview follows the thumb while dragging, before the value is committed.
    val size = rememberSliderEdit(typography.sizePercent.toFloat())
    val weight = rememberSliderEdit(typography.weight.toFloat())

    AppScrollPage(
        title = stringResource(R.string.tweaks_slider_show_percentage_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.settings_aod_icons_back),
    ) {
        AppSection {
            OverlayDropdownPreference(
                title = stringResource(R.string.slider_percentage_display),
                items = listOf(stringResource(R.string.slider_percentage_off), stringResource(R.string.slider_percentage_cc_only), stringResource(R.string.slider_percentage_popup_only), stringResource(R.string.slider_percentage_both)),
                selectedIndex = appSettings.sliderDisplayMode,
                onSelectedIndexChange = { appSettings.sliderDisplayMode = it }
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.slider_percentage_values),
                summary = stringResource(R.string.slider_percentage_values_summary),
                items = listOf(stringResource(R.string.slider_percentage_brightness_only), stringResource(R.string.slider_percentage_volume_only), stringResource(R.string.slider_percentage_both)),
                selectedIndex = appSettings.sliderValueMode,
                onSelectedIndexChange = { appSettings.sliderValueMode = it },
                enabled = appSettings.sliderDisplayMode == 1 || appSettings.sliderDisplayMode == 3
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.slider_percentage_color),
                items = listOf(stringResource(R.string.slider_percentage_normal), stringResource(R.string.slider_percentage_unified)),
                selectedIndex = if (appSettings.sliderSamePercentageStyle) 1 else 0,
                onSelectedIndexChange = { appSettings.sliderSamePercentageStyle = it == 1 },
                enabled = appSettings.sliderDisplayMode != 0
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.slider_percentage_popup_style),
                summary = stringResource(R.string.slider_percentage_popup_style_summary),
                items = listOf(stringResource(R.string.slider_percentage_normal), stringResource(R.string.slider_percentage_blur), stringResource(R.string.slider_percentage_glass)),
                selectedIndex = appSettings.sliderPopupStyle,
                onSelectedIndexChange = { appSettings.sliderPopupStyle = it },
                enabled = appSettings.sliderDisplayMode in 2..3 && appSettings.sliderPopupPosition == 1
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.slider_percentage_popup_position),
                items = listOf(stringResource(R.string.slider_percentage_inside), stringResource(R.string.slider_percentage_outside)),
                selectedIndex = appSettings.sliderPopupPosition,
                onSelectedIndexChange = { appSettings.sliderPopupPosition = it },
                enabled = appSettings.sliderDisplayMode in 2..3
            )
        }
        Spacer(Modifier.height(12.dp))
        AppSection {
            SwitchPreference(
                checked = typography.enabled,
                onCheckedChange = { update(SystemUiSettingsKeys.KEY_SLIDER_TYPOGRAPHY, it, typography.copy(enabled = it)) },
                title = stringResource(R.string.slider_percentage_typography),
                summary = stringResource(R.string.slider_percentage_typography_summary),
                enabled = appSettings.sliderDisplayMode != 0
            )
            MiuixExpandableContent(visible = typography.enabled && appSettings.sliderDisplayMode != 0) {
                SliderPercentagePreview(
                    sizePercent = snap(size.value, SIZE_STEP, SIZE_RANGE),
                    weight = snap(weight.value, WEIGHT_STEP, WEIGHT_RANGE)
                )
                TypographySlider(
                    title = stringResource(R.string.slider_percentage_size),
                    slider = size,
                    range = SIZE_RANGE,
                    step = SIZE_STEP,
                    defaultValue = SliderPercentageTypography.DEFAULT_SIZE_PERCENT,
                    label = { "$it%" }
                ) { update(SystemUiSettingsKeys.KEY_SLIDER_SIZE_PERCENT, it, typography.copy(sizePercent = it)) }
                TypographySlider(
                    title = stringResource(R.string.slider_percentage_weight),
                    slider = weight,
                    range = WEIGHT_RANGE,
                    step = WEIGHT_STEP,
                    defaultValue = SliderPercentageTypography.DEFAULT_WEIGHT,
                    label = { it.toString() }
                ) { update(SystemUiSettingsKeys.KEY_SLIDER_WEIGHT, it, typography.copy(weight = it)) }
            }
        }
    }
}

private val SIZE_RANGE = SliderPercentageTypography.MIN_SIZE_PERCENT..SliderPercentageTypography.MAX_SIZE_PERCENT
private val WEIGHT_RANGE = SliderPercentageTypography.MIN_WEIGHT..SliderPercentageTypography.MAX_WEIGHT
private const val SIZE_STEP = 5
private const val WEIGHT_STEP = 50

private fun snap(raw: Float, step: Int, range: IntRange): Float =
    ((raw / step).roundToInt() * step).coerceIn(range.first, range.last).toFloat()

@Composable
private fun TypographySlider(
    title: String,
    slider: SliderEditState,
    range: IntRange,
    step: Int,
    defaultValue: Int,
    label: (Int) -> String,
    onValue: (Int) -> Unit
) {
    BasicComponent(
        title = title,
        endActions = {
            Text(label(snap(slider.value, step, range).toInt()), color = MiuixTheme.colorScheme.onSurfaceVariantActions)
        },
        bottomAction = {
            Slider(
                value = slider.value.coerceIn(range.first.toFloat(), range.last.toFloat()),
                onValueChange = slider::update,
                onValueChangeFinished = { slider.finish { snap(it, step, range) }?.let { onValue(it.toInt()) } },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                // The host default is a magnetic key point; release still snaps to [step].
                showKeyPoints = true,
                keyPoints = listOf(defaultValue.toFloat()),
                magnetThreshold = 0.02f,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step
            )
        }
    )
}
