package com.takekazex.hypertweak.ui.page.systemui

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
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.SliderPercentageTypography
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.slider.SliderEditState
import com.takekazex.hypertweak.ui.components.slider.rememberSliderEdit
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Brightness/volume slider percentages: where they show, their colours, popup capsule and typography. */
@Composable
fun SliderPercentagePage(
    onBack: () -> Unit,
    sliderDisplayMode: Int,
    onSliderDisplayModeChange: (Int) -> Unit,
    sliderValueMode: Int,
    onSliderValueModeChange: (Int) -> Unit,
    sliderPopupStyle: Int,
    onSliderPopupStyleChange: (Int) -> Unit,
    sliderPopupPosition: Int,
    onSliderPopupPositionChange: (Int) -> Unit,
    sliderSamePercentageStyle: Boolean,
    onSliderSamePercentageChange: (Boolean) -> Unit
) {
    val settingsStore = LocalAppSettingsStore.current
    val scrollBehavior = MiuixScrollBehavior()
    var typography by remember {
        mutableStateOf(SliderPercentageTypography.read(settingsStore::getBoolean, settingsStore::getInt))
    }
    fun update(key: String, value: Any, next: SliderPercentageTypography) {
        typography = next.safe()
        when (value) {
            is Boolean -> settingsStore.putBoolean(key, value)
            is Int -> settingsStore.putInt(key, value)
        }
        settingsStore.flush()
    }
    // Hoisted so the preview follows the thumb while dragging, before the value is committed.
    val size = rememberSliderEdit(typography.sizePercent.toFloat())
    val weight = rememberSliderEdit(typography.weight.toFloat())

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.tweaks_slider_show_percentage_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.settings_aod_icons_back))
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
                Column(Modifier.fillMaxWidth()) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.slider_percentage_display),
                        items = listOf(stringResource(R.string.slider_percentage_off), stringResource(R.string.slider_percentage_cc_only), stringResource(R.string.slider_percentage_popup_only), stringResource(R.string.slider_percentage_both)),
                        selectedIndex = sliderDisplayMode,
                        onSelectedIndexChange = onSliderDisplayModeChange
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.slider_percentage_values),
                        summary = stringResource(R.string.slider_percentage_values_summary),
                        items = listOf(stringResource(R.string.slider_percentage_brightness_only), stringResource(R.string.slider_percentage_volume_only), stringResource(R.string.slider_percentage_both)),
                        selectedIndex = sliderValueMode,
                        onSelectedIndexChange = onSliderValueModeChange,
                        enabled = sliderDisplayMode == 1 || sliderDisplayMode == 3
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.slider_percentage_color),
                        items = listOf(stringResource(R.string.slider_percentage_normal), stringResource(R.string.slider_percentage_unified)),
                        selectedIndex = if (sliderSamePercentageStyle) 1 else 0,
                        onSelectedIndexChange = { onSliderSamePercentageChange(it == 1) },
                        enabled = sliderDisplayMode != 0
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.slider_percentage_popup_style),
                        summary = stringResource(R.string.slider_percentage_popup_style_summary),
                        items = listOf(stringResource(R.string.slider_percentage_normal), stringResource(R.string.slider_percentage_blur), stringResource(R.string.slider_percentage_glass)),
                        selectedIndex = sliderPopupStyle,
                        onSelectedIndexChange = onSliderPopupStyleChange,
                        enabled = sliderDisplayMode in 2..3 && sliderPopupPosition == 1
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.slider_percentage_popup_position),
                        items = listOf(stringResource(R.string.slider_percentage_inside), stringResource(R.string.slider_percentage_outside)),
                        selectedIndex = sliderPopupPosition,
                        onSelectedIndexChange = onSliderPopupPositionChange,
                        enabled = sliderDisplayMode in 2..3
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = typography.enabled,
                        onCheckedChange = { update(SystemUiSettingsKeys.KEY_SLIDER_TYPOGRAPHY, it, typography.copy(enabled = it)) },
                        title = stringResource(R.string.slider_percentage_typography),
                        summary = stringResource(R.string.slider_percentage_typography_summary),
                        enabled = sliderDisplayMode != 0
                    )
                    MiuixExpandableContent(visible = typography.enabled && sliderDisplayMode != 0) {
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
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
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
