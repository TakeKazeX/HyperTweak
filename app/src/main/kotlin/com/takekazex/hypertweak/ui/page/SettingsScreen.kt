package com.takekazex.hypertweak.ui.page

import android.os.Build
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
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur

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
    onNavigateToAbout: () -> Unit
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val topBarBackdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val topAppBarScrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "Settings",
                modifier = Modifier.textureBlur(
                    backdrop = topBarBackdrop,
                    shape = RectangleShape,
                    blurRadius = 25f,
                    colors = BlurDefaults.blurColors(blendColors = listOf(
                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f))
                    ))
                ),
                color = Color.Transparent,
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(topBarBackdrop)
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
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val context = LocalContext.current
                            val systemAccentColor = remember(context) { getSystemAccentColor(context) }
                            val colors = buildList {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(0) // Device color
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
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(displayColor)
                                        .clickable { onSeedColorChange(colorVal) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = MiuixIcons.Basic.Check,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp),
                                            contentDescription = "Selected"
                                        )
                                    } else if (colorVal == 0) {
                                        Text(
                                            text = "D",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
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
                        summary = "Inject an entry point for Ink Tweaks in the system Settings app"
                    )

                    SwitchPreference(
                        checked = hideLauncherIcon,
                        onCheckedChange = onHideLauncherIconChange,
                        title = "Hide Desktop Icon",
                        summary = "Hide launcher icon (access module via LSPosed or system settings)"
                    )
                }
            }

            // Other
            SmallTitle(text = "Other")
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                ArrowPreference(
                    title = "About",
                    summary = "Ink Tweaks v1.0",
                    onClick = onNavigateToAbout
                )
            }

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
