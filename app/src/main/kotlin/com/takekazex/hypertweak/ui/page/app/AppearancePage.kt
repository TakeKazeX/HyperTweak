package com.takekazex.hypertweak.ui.page.app

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppSettings
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.chrome.BottomBarStyle
import com.takekazex.hypertweak.ui.chrome.appUiStyleFromStored
import com.takekazex.hypertweak.ui.chrome.bottomBarStyle
import com.takekazex.hypertweak.ui.chrome.bottomBarStyles
import com.takekazex.hypertweak.ui.chrome.stored
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.dialog.ScaleDialog
import com.takekazex.hypertweak.ui.components.slider.rememberSliderEdit
import com.takekazex.hypertweak.ui.theme.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Covers the glass dropdown's closing motion (200 ms fade, ~0.35 s bounds spring). */
private const val UI_STYLE_SWITCH_DELAY_MS = 420L

@Composable
fun AppearancePage(
    onBack: () -> Unit,
) {
    val appSettings = LocalAppSettings.current
    val resources = LocalResources.current
    // Switching the interface style swaps the whole chrome, including this dropdown (glass <->
    // classic). Applied at once, the swap lands mid-way through the popup's closing spring and the
    // popup jumps; show the pick now and apply it once the popup has settled, or on leaving.
    var pendingUiStyle by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingUiStyle) {
        val style = pendingUiStyle ?: return@LaunchedEffect
        delay(UI_STYLE_SWITCH_DELAY_MS)
        appSettings.appUiStyle = style
        pendingUiStyle = null
    }
    DisposableEffect(Unit) {
        onDispose { pendingUiStyle?.let { appSettings.appUiStyle = it } }
    }
    val systemAccent = rememberDeviceAccentColor()
    val resolvedSeed = if (appSettings.seedColorHex == 0) systemAccent else appSettings.seedColorHex
    val darkPreview = isEffectivelyDark(appSettings.themeMode, isSystemInDarkTheme())
    val customSeed = appSettings.seedColorHex !in presetAccentColors
    val accentLabels = remember(resources, appSettings.seedColorHex, customSeed) {
        buildList {
            addAll(presetAccentLabels.map { resources.getString(it) })
            if (customSeed) {
                add(resources.getString(R.string.appearance_custom_accent, formatSeedColor(appSettings.seedColorHex)))
            }
        }
    }
    val accentColors = remember(appSettings.seedColorHex, systemAccent, customSeed) {
        buildList {
            add(Color(systemAccent))
            presetAccentColors.drop(1).forEach { add(Color(it)) }
            if (customSeed) add(Color(appSettings.seedColorHex))
        }
    }
    val selectedAccentIndex = presetAccentColors.indexOf(appSettings.seedColorHex)
        .takeIf { it >= 0 } ?: presetAccentColors.size
    val accentEntry = DropdownEntry(
        items = accentLabels.mapIndexed { index, label ->
            DropdownItem(
                text = label,
                selected = index == selectedAccentIndex,
                onClick = {
                    appSettings.seedColorHex = if (index in presetAccentColors.indices) presetAccentColors[index] else appSettings.seedColorHex
                },
                icon = { modifier ->
                    Box(
                        modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(accentColors[index])
                    )
                }
            )
        }
    )
    // Each entry resolves through Miuix's public composable, so the swatches follow the same
    // seed-to-palette derivation the applied theme uses.
    val palettePreviewColors = PaletteStyleOption.entries.map { option ->
        MiuixSpec2025Adapter.previewColors(resolvedSeed, option.persistedId, darkPreview)
    }
    val paletteEntry = DropdownEntry(
        items = PaletteStyleOption.entries.mapIndexed { index, option ->
            DropdownItem(
                text = stringResource(option.labelRes),
                selected = index == paletteStyleIndex(appSettings.paletteStyle),
                onClick = { appSettings.paletteStyle = option.persistedId },
                icon = { modifier ->
                    Row(
                        modifier = modifier.width(42.dp).height(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        palettePreviewColors[index].forEach { color ->
                            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                        }
                    }
                }
            )
        }
    )

    AppScrollPage(
        title = stringResource(R.string.appearance_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.appearance_back),
    ) {
        AppSection(title = stringResource(R.string.appearance_theme)) {
            OverlayDropdownPreference(
                title = stringResource(R.string.appearance_ui_style),
                summary = stringResource(R.string.appearance_ui_style_summary),
                items = listOf(
                    stringResource(R.string.appearance_ui_style_os3),
                    stringResource(R.string.appearance_ui_style_os4)
                ),
                selectedIndex = (pendingUiStyle ?: appSettings.appUiStyle).coerceIn(0, 1),
                onSelectedIndexChange = { pendingUiStyle = it.takeIf { pick -> pick != appSettings.appUiStyle } }
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.appearance_theme_mode),
                items = listOf(
                    stringResource(R.string.appearance_theme_follow_system),
                    stringResource(R.string.appearance_theme_light),
                    stringResource(R.string.appearance_theme_dark)
                ),
                selectedIndex = appSettings.themeMode.coerceIn(0, 2),
                onSelectedIndexChange = { appSettings.themeMode = it }
            )
            SwitchPreference(
                checked = appSettings.useMonet,
                onCheckedChange = { appSettings.useMonet = it },
                title = stringResource(R.string.appearance_monet_color),
                summary = stringResource(R.string.appearance_monet_color_summary)
            )
            MiuixExpandableContent(visible = appSettings.useMonet) {
                Column(Modifier.fillMaxWidth()) {
                    OverlayDropdownPreference(entry = accentEntry, title = stringResource(R.string.appearance_accent_color))
                    OverlayDropdownPreference(entry = paletteEntry, title = stringResource(R.string.appearance_color_style))
                }
            }
            SwitchPreference(
                checked = appSettings.pureBlackDarkTheme,
                onCheckedChange = { appSettings.pureBlackDarkTheme = it },
                title = stringResource(R.string.appearance_pure_black_background),
                summary = stringResource(R.string.appearance_pure_black_background_summary)
            )
        }

        AppSection(title = stringResource(R.string.appearance_navigation)) {
            val uiStyle = appUiStyleFromStored(appSettings.appUiStyle)
            val barStyles = bottomBarStyles(uiStyle)
            val barStyle = bottomBarStyle(uiStyle, appSettings.useFloatingBottomBar, appSettings.floatingBarStyle)
            OverlayDropdownPreference(
                title = stringResource(R.string.appearance_bottom_bar_style),
                items = barStyles.map { stringResource(it.labelRes()) },
                selectedIndex = barStyles.indexOf(barStyle).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    val (floating, style) = barStyles[index].stored()
                    appSettings.useFloatingBottomBar = floating
                    if (floating) appSettings.floatingBarStyle = style
                }
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.appearance_back_style),
                items = listOf(
                    stringResource(R.string.appearance_back_style_disabled),
                    stringResource(R.string.appearance_back_style_miuix),
                    stringResource(R.string.appearance_back_style_scale)
                ),
                selectedIndex = appSettings.predictiveBackStyle.coerceIn(0, 2),
                onSelectedIndexChange = { appSettings.predictiveBackStyle = it }
            )
            MiuixExpandableContent(visible = appSettings.predictiveBackStyle == 2) {
                SwitchPreference(
                    checked = appSettings.predictiveBackFollowGesture,
                    onCheckedChange = { appSettings.predictiveBackFollowGesture = it },
                    title = stringResource(R.string.appearance_follow_gesture_direction),
                    summary = stringResource(R.string.appearance_follow_gesture_direction_summary)
                )
            }
        }

        AppSection(title = stringResource(R.string.appearance_display)) {
            OverlayDropdownPreference(
                title = stringResource(R.string.appearance_global_blur),
                items = listOf(
                    stringResource(R.string.appearance_blur_off),
                    stringResource(R.string.appearance_blur_uniform),
                    stringResource(R.string.appearance_blur_progressive),
                ),
                selectedIndex = appSettings.appBlurMode.coerceIn(0, 2),
                onSelectedIndexChange = { appSettings.appBlurMode = it },
            )
            val slider = rememberSliderEdit(appSettings.pageScale)
            val sliderValue = slider.value
            var expanded by remember { mutableStateOf(false) }
            ArrowPreference(
                title = stringResource(R.string.appearance_interface_scale),
                summary = stringResource(R.string.appearance_interface_scale_summary),
                endActions = {
                    Text("${(sliderValue * 100).toInt()}%", color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                },
                onClick = { expanded = !expanded },
                holdDownState = expanded,
                bottomAction = {
                    Slider(
                        value = sliderValue,
                        onValueChange = slider::update,
                        onValueChangeFinished = { slider.finish()?.let({ appSettings.pageScale = it }) },
                        valueRange = 0.85f..1.15f,
                        showKeyPoints = true,
                        keyPoints = listOf(0.85f, 1f, 1.15f),
                        magnetThreshold = 0.01f,
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step
                    )
                }
            )
            ScaleDialog(
                show = expanded,
                onDismissRequest = { expanded = false },
                volumeState = { appSettings.pageScale },
                onVolumeChange = { appSettings.pageScale = it },
            )
        }
    }
}

private fun BottomBarStyle.labelRes(): Int = when (this) {
    BottomBarStyle.Glass -> R.string.appearance_bar_style_glass
    BottomBarStyle.Miuix -> R.string.appearance_bar_style_miuix
    BottomBarStyle.MiuixFloating -> R.string.appearance_bar_style_miuix_floating
    BottomBarStyle.IosLiquid -> R.string.appearance_bar_style_ios
}
