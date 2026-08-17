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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.takekazex.hypertweak.getSystemAccentColor
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.systemui.LockscreenChargingDetailHooker
import com.takekazex.hypertweak.util.LauncherVersion
import com.takekazex.hypertweak.util.PlatformLevel
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
    immediateMonetRefresh: Boolean,
    lockscreenFingerprintAvoid: Int,
    onLockscreenFingerprintAvoidChange: (Int) -> Unit,
    lockscreenChargingDetail: Boolean,
    onLockscreenChargingDetailChange: (Boolean) -> Unit,
    lockscreenChargingDetailFields: Int,
    onLockscreenChargingDetailFieldsChange: (Int) -> Unit,
    lockscreenChargingDetailIntervalMs: Int,
    onLockscreenChargingDetailIntervalChange: (Int) -> Unit,
    lockscreenChargingDetailMultiline: Boolean,
    onLockscreenChargingDetailMultilineChange: (Boolean) -> Unit,
    launcherMajor: Int,
    launcherSupportsBackRoute: Boolean,
    aospBackMiuiHomeHooks: Boolean,
    onAospBackMiuiHomeHooksChange: (Boolean) -> Unit,
    onNavigateToPredictiveBackApps: () -> Unit,
    onNavigateToAospRestore: () -> Unit,
    onImmediateMonetRefreshChange: (Boolean) -> Unit,
    onNavigateToIconTuner: () -> Unit,
    onNavigateToGlassTuner: () -> Unit,
    onNavigateToWatermark: () -> Unit,
    themeSummary: String,
    onNavigateToAppearance: () -> Unit,
    allowLandscape: Boolean,
    onAllowLandscapeChange: (Boolean) -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToDebugLogs: () -> Unit,
    onNavigateToAppShortcuts: () -> Unit,
    onClearAllSettings: () -> Unit,
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
    var showClearAllDialog by remember { mutableStateOf(false) }
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

            SmallTitle(text = "Appearance")
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                ArrowPreference(
                    title = "Appearance",
                    summary = themeSummary,
                    onClick = onNavigateToAppearance
                )
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

                    AnimatedVisibility(
                        visible = !hideLauncherIcon,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        ArrowPreference(
                            title = "App Shortcuts",
                            summary = "Choose shortcuts shown in long-press app icon menu",
                            onClick = onNavigateToAppShortcuts
                        )
                    }

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

            SmallTitle(text = "Experimental")
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = immediateMonetRefresh,
                        onCheckedChange = onImmediateMonetRefreshChange,
                        title = "Immediate Monet Refresh",
                        summary = "Apply new wallpaper colors when HyperOS misses or delays its Monet update"
                    )
                    // The notification-stack fingerprint avoidance anchors on the OS4
                    // `nsslLockYPosition` combine; OS3's keyguard uses a different container.
                    if (PlatformLevel.isOs4) {
                        OverlayDropdownPreference(
                            title = "Lockscreen Fingerprint Avoid",
                            summary = "Keep lockscreen notifications clear of the in-display fingerprint icon",
                            items = listOf("System Default", "No Avoidance", "Always Avoid"),
                            selectedIndex = lockscreenFingerprintAvoid.coerceIn(0, 2),
                            onSelectedIndexChange = onLockscreenFingerprintAvoidChange
                        )
                    }
                    // Appends live charging telemetry (wattage / voltage / current / temperature)
                    // to the bottom lockscreen indication, OS4 SystemUI. Sub-options apply live;
                    // only the master switch needs a SystemUI restart.
                    if (PlatformLevel.isOs4) {
                        SwitchPreference(
                            checked = lockscreenChargingDetail,
                            onCheckedChange = onLockscreenChargingDetailChange,
                            title = "Charging Detail on Lockscreen",
                            summary = "Show live charging telemetry under the lockscreen charging text"
                        )
                        AnimatedVisibility(
                            visible = lockscreenChargingDetail,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                SwitchPreference(
                                    checked = lockscreenChargingDetailMultiline,
                                    onCheckedChange = onLockscreenChargingDetailMultilineChange,
                                    title = "Multi-line Layout",
                                    summary = "Put the telemetry on its own line below the charging text instead of extending the scrolling single line"
                                )
                                OverlayDropdownPreference(
                                    title = "Refresh Interval",
                                    summary = "How often the telemetry values update",
                                    items = listOf("1 second", "2 seconds", "3 seconds", "5 seconds"),
                                    selectedIndex = when (lockscreenChargingDetailIntervalMs) {
                                        1000 -> 0
                                        3000 -> 2
                                        5000 -> 3
                                        else -> 1
                                    },
                                    onSelectedIndexChange = { index ->
                                        listOf(1000, 2000, 3000, 5000).getOrNull(index)
                                            ?.let(onLockscreenChargingDetailIntervalChange)
                                    }
                                )
                                chargingFieldSwitch(
                                    checked = (lockscreenChargingDetailFields and
                                        LockscreenChargingDetailHooker.FIELD_WATTAGE) != 0,
                                    title = "Wattage",
                                    summary = "Real-time charging power (W)",
                                    onChange = onLockscreenChargingDetailFieldsChange,
                                    current = lockscreenChargingDetailFields,
                                    bit = LockscreenChargingDetailHooker.FIELD_WATTAGE
                                )
                                chargingFieldSwitch(
                                    checked = (lockscreenChargingDetailFields and
                                        LockscreenChargingDetailHooker.FIELD_VOLTAGE) != 0,
                                    title = "Voltage",
                                    summary = "Battery voltage (V)",
                                    onChange = onLockscreenChargingDetailFieldsChange,
                                    current = lockscreenChargingDetailFields,
                                    bit = LockscreenChargingDetailHooker.FIELD_VOLTAGE
                                )
                                chargingFieldSwitch(
                                    checked = (lockscreenChargingDetailFields and
                                        LockscreenChargingDetailHooker.FIELD_CURRENT) != 0,
                                    title = "Current",
                                    summary = "Charging current (A)",
                                    onChange = onLockscreenChargingDetailFieldsChange,
                                    current = lockscreenChargingDetailFields,
                                    bit = LockscreenChargingDetailHooker.FIELD_CURRENT
                                )
                                chargingFieldSwitch(
                                    checked = (lockscreenChargingDetailFields and
                                        LockscreenChargingDetailHooker.FIELD_TEMPERATURE) != 0,
                                    title = "Temperature",
                                    summary = "Battery temperature (°C)",
                                    onChange = onLockscreenChargingDetailFieldsChange,
                                    current = lockscreenChargingDetailFields,
                                    bit = LockscreenChargingDetailHooker.FIELD_TEMPERATURE
                                )
                            }
                        }
                    }
                    ArrowPreference(
                        title = "Icon Tuner",
                        summary = "Hide and customize status-bar icons (cellular, WiFi)",
                        onClick = onNavigateToIconTuner
                    )
                    ArrowPreference(
                        title = "Watermark Unlock",
                        summary = "Unlock Leica / Disney / POCO / festival watermarks in the media editor",
                        onClick = onNavigateToWatermark
                    )
                    // The material style (材质风格) with its two modes only exists on OS4;
                    // OS3 SystemUI has neither the bionics resources nor the material_style key.
                    if (PlatformLevel.isOs4) {
                        ArrowPreference(
                            title = "Glass Material Tuner",
                            summary = "Tune the blur and blend parameters behind 清透磨砂 / 柔光玻璃",
                            onClick = onNavigateToGlassTuner
                        )
                    }
                }
            }

            // Launcher-dependent halves of the AOSP back gesture. The gesture itself and its
            // SystemUI-only options stay under Features; only what hooks com.miui.home lives here.
            // Hidden on OS4 together with the AOSP back gesture feature.
            if (!PlatformLevel.isOs4) {
                SmallTitle(text = "Launcher Hooks")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    SwitchPreference(
                        checked = aospBackMiuiHomeHooks && launcherSupportsBackRoute,
                        onCheckedChange = onAospBackMiuiHomeHooksChange,
                        title = "Predictive Return to Home",
                        summary = launcherBackRouteSummary(launcherMajor, launcherSupportsBackRoute),
                        enabled = launcherSupportsBackRoute
                    )
                    ArrowPreference(
                        title = "Predictive Back Apps",
                        summary = "Force predictive back for apps that never opted in",
                        onClick = onNavigateToPredictiveBackApps
                    )
                }
            }

            SmallTitle(text = "AOSP Restore")
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                ArrowPreference(
                    title = "AOSP Restore",
                    summary = "Hand HyperOS components back to their AOSP implementations",
                    onClick = onNavigateToAospRestore
                )
            }

            // Other
            SmallTitle(text = "Other")
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                ArrowPreference(
                    title = "Debug",
                    summary = "Logging and diagnostics",
                    onClick = onNavigateToDebugLogs
                )
                ArrowPreference(
                    title = "About",
                    summary = "HyperTweak v${BuildConfig.VERSION_NAME}",
                    onClick = onNavigateToAbout
                )
                ArrowPreference(
                    title = "Clear All Settings",
                    summary = "Reset every setting, including the LSPosed-side copy that survives uninstall",
                    onClick = { showClearAllDialog = true }
                )
            }

            ClearAllSettingsDialog(
                show = showClearAllDialog,
                onDismissRequest = { showClearAllDialog = false },
                onConfirm = {
                    showClearAllDialog = false
                    onClearAllSettings()
                }
            )

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
private fun ClearAllSettingsDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    OverlayDialog(
        show = show,
        title = "Clear All Settings",
        summary = "This resets every module setting to its default and also wipes the copy " +
            "stored by the LSPosed service, which normally survives module uninstall. " +
            "Hooked processes pick the defaults up without a reboot.",
        onDismissRequest = onDismissRequest,
        content = {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = "Cancel",
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "Clear",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    )
}

/**
 * The predictive return-home animation hooks `com.miui.home` Java classes that only Launcher 7
 * and older ship, so explain why the switch is unavailable rather than just greying it out.
 */
private fun launcherBackRouteSummary(launcherMajor: Int, supported: Boolean): String {
    val version = LauncherVersion.versionName.ifBlank { "unknown" }
    return when {
        // Upstream documents the launcher animation hooks as matched to 7.50.xx. Other 7.x
        // builds move the members it resolves, so show the exact version to compare against.
        supported -> "Hand the back-to-home gesture to the launcher animation. " +
            "Installed launcher $version; upstream targets 7.50.xx"
        launcherMajor > 0 ->
            "Unavailable: launcher $version has no hookable gesture code (needs Launcher 7)"
        else -> "Unavailable: could not detect the installed launcher"
    }
}

/** Toggle one bit of the charging-detail field bitmask. */
@Composable
private fun chargingFieldSwitch(
    checked: Boolean,
    title: String,
    summary: String,
    onChange: (Int) -> Unit,
    current: Int,
    bit: Int
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = { on -> onChange(if (on) current or bit else current and bit.inv()) },
        title = title,
        summary = summary
    )
}
