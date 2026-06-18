package com.takekazex.hypertweak.ui.page

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.takekazex.hypertweak.getSystemAccentColor
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.R
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import com.takekazex.hypertweak.ui.effect.rememberContentReady
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio

@Composable
fun SettingsScreenContent(
    padding: PaddingValues,
    showInSettings: Boolean,
    onShowInSettingsChange: (Boolean) -> Unit,
    hideLauncherIcon: Boolean,
    onHideLauncherIconChange: (Boolean) -> Unit,
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
    useMonet: Boolean,
    onUseMonetChange: (Boolean) -> Unit,
    seedColorHex: Int,
    onSeedColorChange: (Int) -> Unit,
    useFloatingBottomBar: Boolean,
    onUseFloatingBottomBarChange: (Boolean) -> Unit,
    floatingBarStyle: Int,
    onFloatingBarStyleChange: (Int) -> Unit,
    predictiveBackStyle: Int,
    onPredictiveBackStyleChange: (Int) -> Unit,
    predictiveBackFollowGesture: Boolean,
    onPredictiveBackFollowGestureChange: (Boolean) -> Unit,
    allowLandscape: Boolean,
    onAllowLandscapeChange: (Boolean) -> Unit,
    pageScale: Float,
    onPageScaleChange: (Float) -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
    onNavigateToAppShortcuts: () -> Unit,
    backdrop: LayerBackdrop,
    appLanguage: Int,
    onAppLanguageChange: (Int) -> Unit
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val topBarBackdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val contentReady = rememberContentReady()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "Settings",
                modifier = if (contentReady) {
                    Modifier.textureBlur(
                        backdrop = topBarBackdrop,
                        shape = RectangleShape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(blendColors = listOf(
                            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f))
                        ))
                    )
                } else {
                    Modifier
                },
                color = Color.Transparent,
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .then(if (contentReady) Modifier.layerBackdrop(topBarBackdrop) else Modifier)
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Spacer(modifier = Modifier.height(innerPadding.calculateTopPadding()))
            Spacer(modifier = Modifier.height(8.dp))

            // Theme Settings
            SmallTitle(text = "Theme Settings")
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OverlayDropdownPreference(
                        title = "Theme Mode",
                        items = listOf("Follow System", "Light", "Dark"),
                        selectedIndex = themeMode,
                        onSelectedIndexChange = onThemeModeChange
                    )

                    SwitchPreference(
                        checked = useMonet,
                        onCheckedChange = onUseMonetChange,
                        title = "Use Monet Accent Color",
                        summary = "Enable dynamic colors based on selected accent color"
                    )

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
                            selectedIndex = floatingBarStyle,
                            onSelectedIndexChange = onFloatingBarStyleChange
                        )
                    }

                    OverlayDropdownPreference(
                        title = "Predictive Back Style",
                        items = listOf("Disabled", "Miuix", "Scale"),
                        selectedIndex = predictiveBackStyle,
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
                            summary = "Adjust scale pivot and exit animation translation based on swipe edge"
                        )
                    }

                    var sliderValue by remember(pageScale) { mutableFloatStateOf(pageScale) }
                    var showScaleDialog by remember { mutableStateOf(false) }

                    ArrowPreference(
                        title = "Interface Scale",
                        summary = "Adjust the size of application interface elements",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.AspectRatio,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "Interface Scale",
                                tint = MiuixTheme.colorScheme.onBackground
                            )
                        },
                        endActions = {
                            Text(
                                text = "${(sliderValue * 100).toInt()}%",
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = { showScaleDialog = !showScaleDialog },
                        holdDownState = showScaleDialog,
                        bottomAction = {
                            Slider(
                                value = sliderValue,
                                onValueChange = {
                                    sliderValue = it
                                },
                                onValueChangeFinished = {
                                    onPageScaleChange(sliderValue)
                                },
                                valueRange = 0.85f..1.15f,
                                showKeyPoints = true,
                                keyPoints = listOf(0.85f, 1.0f, 1.15f),
                                magnetThreshold = 0.01f,
                                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                            )
                        }
                    )

                    ScaleDialog(
                        show = showScaleDialog,
                        onDismissRequest = { showScaleDialog = false },
                        volumeState = { pageScale },
                        onVolumeChange = onPageScaleChange
                    )
                }
            }

            // Accent Color Selection
            AnimatedVisibility(
                visible = useMonet,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SmallTitle(text = "Accent Color Selection")
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val context = LocalContext.current
                            val systemAccentColor = remember(context) { getSystemAccentColor(context) }
                            val colors = buildList {
                                add(0) // Device color
                                add(0xFF007AFF.toInt()) // Blue
                                add(0xFF4CAF50.toInt()) // Green
                                add(0xFFFF9800.toInt()) // Orange
                                add(0xFFF44336.toInt()) // Red
                                add(0xFF9C27B0.toInt()) // Purple
                                add(0xFF3F51B5.toInt()) // Indigo
                            }

                            colors.forEach { colorVal ->
                                val isSelected = seedColorHex == colorVal
                                val displayColor = if (colorVal == 0) Color(systemAccentColor) else Color(colorVal)
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(displayColor)
                                        .clickable { onSeedColorChange(colorVal) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = MiuixIcons.Basic.Check,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp),
                                            contentDescription = "Selected"
                                        )
                                    } else if (colorVal == 0) {
                                        Text(
                                            text = "D",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Module Preferences
            SmallTitle(text = "Module Preferences")
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = showInSettings,
                        onCheckedChange = onShowInSettingsChange,
                        title = "Show Entry in System Settings",
                        summary = "Inject an entry point for HyperTweak in the system Settings app"
                    )

                    SwitchPreference(
                        checked = hideLauncherIcon,
                        onCheckedChange = onHideLauncherIconChange,
                        title = "Hide Desktop Icon",
                        summary = "Hide launcher icon (access module via LSPosed or system settings)"
                    )

                    ArrowPreference(
                        title = "App Shortcuts",
                        summary = "Choose shortcuts shown in long-press app icon menu",
                        onClick = onNavigateToAppShortcuts
                    )

                    SwitchPreference(
                        checked = allowLandscape,
                        onCheckedChange = onAllowLandscapeChange,
                        title = "Allow Landscape Mode",
                        summary = "Enable rotation to horizontal screen orientation"
                    )

                    OverlayDropdownPreference(
                        title = stringResource(id = R.string.pref_language_title),
                        items = listOf(
                            stringResource(id = R.string.pref_language_device_default),
                            stringResource(id = R.string.pref_language_zh_cn),
                            stringResource(id = R.string.pref_language_en)
                        ),
                        selectedIndex = appLanguage,
                        onSelectedIndexChange = onAppLanguageChange
                    )
                }
            }

            // Other
            SmallTitle(text = "Other")
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                ArrowPreference(
                    title = "Debug Logs",
                    summary = "View module and hook runtime logs",
                    onClick = onNavigateToDebugLogs
                )
                ArrowPreference(
                    title = "About",
                    summary = "HyperTweak v${BuildConfig.VERSION_NAME}",
                    onClick = onNavigateToAbout
                )
            }

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
