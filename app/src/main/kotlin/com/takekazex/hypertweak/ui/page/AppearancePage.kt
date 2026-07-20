package com.takekazex.hypertweak.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.ui.theme.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun AppearancePage(
    onBack: () -> Unit,
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
    useMonet: Boolean,
    onUseMonetChange: (Boolean) -> Unit,
    seedColorHex: Int,
    onSeedColorChange: (Int) -> Unit,
    paletteStyle: Int,
    onPaletteStyleChange: (Int) -> Unit,
    pureBlackDarkTheme: Boolean,
    onPureBlackDarkThemeChange: (Boolean) -> Unit,
    useFloatingBottomBar: Boolean,
    onUseFloatingBottomBarChange: (Boolean) -> Unit,
    floatingBarStyle: Int,
    onFloatingBarStyleChange: (Int) -> Unit,
    predictiveBackStyle: Int,
    onPredictiveBackStyleChange: (Int) -> Unit,
    predictiveBackFollowGesture: Boolean,
    onPredictiveBackFollowGestureChange: (Boolean) -> Unit,
    pageScale: Float,
    onPageScaleChange: (Float) -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    val systemAccent = rememberDeviceAccentColor()
    val resolvedSeed = if (seedColorHex == 0) systemAccent else seedColorHex
    val darkPreview = isEffectivelyDark(themeMode, isSystemInDarkTheme())
    val customSeed = seedColorHex !in presetAccentColors
    val accentLabels = remember(seedColorHex, customSeed) {
        buildList {
            addAll(presetAccentLabels)
            if (customSeed) add("Custom (${formatSeedColor(seedColorHex)})")
        }
    }
    val accentColors = remember(seedColorHex, systemAccent, customSeed) {
        buildList {
            add(Color(systemAccent))
            presetAccentColors.drop(1).forEach { add(Color(it)) }
            if (customSeed) add(Color(seedColorHex))
        }
    }
    val selectedAccentIndex = presetAccentColors.indexOf(seedColorHex)
        .takeIf { it >= 0 } ?: presetAccentColors.size
    val accentEntry = DropdownEntry(
        items = accentLabels.mapIndexed { index, label ->
            DropdownItem(
                text = label,
                selected = index == selectedAccentIndex,
                onClick = {
                    onSeedColorChange(
                        if (index in presetAccentColors.indices) presetAccentColors[index] else seedColorHex
                    )
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
    val palettePreviewColors = remember(resolvedSeed, darkPreview) {
        PaletteStyleOption.entries.map { option ->
            MiuixSpec2025Adapter.previewColors(resolvedSeed, option.persistedId, darkPreview)
        }
    }
    val paletteEntry = DropdownEntry(
        items = PaletteStyleOption.entries.mapIndexed { index, option ->
            DropdownItem(
                text = option.label,
                selected = index == paletteStyleIndex(paletteStyle),
                onClick = { onPaletteStyleChange(option.persistedId) },
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = "Appearance",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(innerPadding.calculateTopPadding() + 8.dp))
            SmallTitle("Theme")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    OverlayDropdownPreference(
                        title = "Theme Mode",
                        items = listOf("Follow System", "Light", "Dark"),
                        selectedIndex = themeMode.coerceIn(0, 2),
                        onSelectedIndexChange = onThemeModeChange
                    )
                    SwitchPreference(
                        checked = useMonet,
                        onCheckedChange = onUseMonetChange,
                        title = "Monet Color",
                        summary = "Generate a Monet palette from the selected accent color"
                    )
                    AnimatedVisibility(
                        visible = useMonet,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            OverlayDropdownPreference(entry = accentEntry, title = "Accent Color")
                            OverlayDropdownPreference(entry = paletteEntry, title = "Color Style")
                        }
                    }
                    SwitchPreference(
                        checked = pureBlackDarkTheme,
                        onCheckedChange = onPureBlackDarkThemeChange,
                        title = "Pure Black Background",
                        summary = "Use pure black for the base background in dark mode"
                    )
                }
            }

            SmallTitle("Navigation")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = useFloatingBottomBar,
                        onCheckedChange = onUseFloatingBottomBarChange,
                        title = "Floating Bottom Bar",
                        summary = "Enable floating style bottom navigation bar"
                    )
                    AnimatedVisibility(
                        visible = useFloatingBottomBar,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        OverlayDropdownPreference(
                            title = "Floating Bottom Bar Style",
                            items = listOf("Miuix", "iOS-like"),
                            selectedIndex = floatingBarStyle.coerceIn(0, 1),
                            onSelectedIndexChange = onFloatingBarStyleChange
                        )
                    }
                    OverlayDropdownPreference(
                        title = "Predictive Back Style",
                        items = listOf("Disabled", "Miuix", "Scale"),
                        selectedIndex = predictiveBackStyle.coerceIn(0, 2),
                        onSelectedIndexChange = onPredictiveBackStyleChange
                    )
                    AnimatedVisibility(
                        visible = predictiveBackStyle == 2,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        SwitchPreference(
                            checked = predictiveBackFollowGesture,
                            onCheckedChange = onPredictiveBackFollowGestureChange,
                            title = "Follow Gesture Direction",
                            summary = "Adjust the transition based on the swipe edge"
                        )
                    }
                }
            }

            SmallTitle("Display")
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                var sliderValue by remember(pageScale) { mutableFloatStateOf(pageScale) }
                var expanded by remember { mutableStateOf(false) }
                ArrowPreference(
                    title = "Interface Scale",
                    summary = "Adjust the size of application interface elements",
                    endActions = {
                        Text("${(sliderValue * 100).toInt()}%", color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                    },
                    onClick = { expanded = !expanded },
                    holdDownState = expanded,
                    bottomAction = {
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = { onPageScaleChange(sliderValue) },
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
                    volumeState = { pageScale },
                    onVolumeChange = onPageScaleChange
                )
            }
            Spacer(Modifier.height(innerPadding.calculateBottomPadding() + 16.dp))
        }
    }
}
