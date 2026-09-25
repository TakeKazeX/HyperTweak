package com.takekazex.hypertweak.ui.page

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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.system.VolumeKeyStepPolicy
import com.takekazex.hypertweak.util.ExtendUnlockLauncher
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.RestartUtils
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Toggles that hand a HyperOS component back to its AOSP implementation.
 *
 * State is kept locally rather than hoisted into `MainActivity`, but changes report their affected
 * process to the shared Home restart dialog; the page also keeps its local restart affordances.
 */
@Composable
fun AospRestorePage(onBack: () -> Unit, onNavigateToAospIme: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current

    var packageInstaller by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_PACKAGE_INSTALLER, false))
    }
    var powerMenu by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_POWER_MENU, false))
    }
    var volumePanel by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_VOLUME_PANEL, false))
    }
    var volumePanelHapticMiui by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_VOLUME_HAPTIC_MIUI, false))
    }
    var volumeKeyStepCount by remember {
        mutableIntStateOf(
            Preferences.getInt(
                Preferences.KEY_VOLUME_KEY_STEP_COUNT,
                Preferences.DEFAULT_VOLUME_KEY_STEP_COUNT
            ).coerceIn(VolumeKeyStepPolicy.MIN_STEPS, VolumeKeyStepPolicy.MAX_STEPS)
        )
    }
    var volumeKeyStepScope by remember {
        mutableIntStateOf(
            Preferences.getInt(
                Preferences.KEY_VOLUME_KEY_STEP_SCOPE,
                Preferences.VOLUME_KEY_SCOPE_MEDIA_ONLY
            ).coerceIn(
                Preferences.VOLUME_KEY_SCOPE_MEDIA_ONLY,
                Preferences.VOLUME_KEY_SCOPE_ACTIVE_STREAM
            )
        )
    }
    var volumeKeySliderExpanded by remember { mutableStateOf(false) }
    var volumeKeySliderValue by remember(volumeKeyStepCount) {
        mutableFloatStateOf(volumeKeyStepCount.toFloat())
    }
    val displayedVolumeKeyStepCount = volumeKeySliderValue.roundToInt().coerceIn(
        VolumeKeyStepPolicy.MIN_STEPS,
        VolumeKeyStepPolicy.MAX_STEPS
    )
    fun setVolumeKeyStepCount(value: Int) {
        val resolved = value.coerceIn(VolumeKeyStepPolicy.MIN_STEPS, VolumeKeyStepPolicy.MAX_STEPS)
        volumeKeyStepCount = resolved
        Preferences.putInt(Preferences.KEY_VOLUME_KEY_STEP_COUNT, resolved)
        Preferences.flush()
    }

    fun setVolumeKeyStepScope(value: Int) {
        val resolved = value.coerceIn(
            Preferences.VOLUME_KEY_SCOPE_MEDIA_ONLY,
            Preferences.VOLUME_KEY_SCOPE_ACTIVE_STREAM
        )
        volumeKeyStepScope = resolved
        Preferences.putInt(Preferences.KEY_VOLUME_KEY_STEP_SCOPE, resolved)
        Preferences.flush()
    }

    var clipboardEditor by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_CLIPBOARD_EDITOR, false))
    }
    var extendUnlockFix by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_EXTEND_UNLOCK_FIX, false))
    }
    var appInfoEntry by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_APP_INFO_ENTRY, false))
    }
    var appManagerEntry by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_AOSP_APP_MANAGER_ENTRY, false))
    }
    // Keep the local prompt for immediate access on this page. Each affected switch also reports
    // its process to the shared Home restart dialog through LocalRestartScopeRequest.
    var systemUiRestartPending by rememberSaveable { mutableStateOf(false) }
    var securityCenterRestartPending by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.aosp_page_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.aosp_back)) } }
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

            SmallTitle(stringResource(R.string.volume_key_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.volume_key_scope_title),
                        summary = stringResource(R.string.volume_key_scope_summary),
                        items = listOf(
                            stringResource(R.string.volume_key_scope_media),
                            stringResource(R.string.volume_key_scope_active_stream)
                        ),
                        selectedIndex = volumeKeyStepScope,
                        onSelectedIndexChange = ::setVolumeKeyStepScope
                    )
                    ArrowPreference(
                        title = stringResource(R.string.volume_key_step_title),
                        summary = stringResource(
                            R.string.volume_key_step_summary,
                            displayedVolumeKeyStepCount,
                            (100f / displayedVolumeKeyStepCount).roundToInt()
                        ),
                        endActions = {
                            Text(
                                text = stringResource(R.string.volume_key_step_value, displayedVolumeKeyStepCount),
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        },
                        onClick = { volumeKeySliderExpanded = !volumeKeySliderExpanded },
                        holdDownState = volumeKeySliderExpanded,
                        bottomAction = {
                            Slider(
                                value = volumeKeySliderValue.coerceIn(
                                    VolumeKeyStepPolicy.MIN_STEPS.toFloat(),
                                    VolumeKeyStepPolicy.MAX_STEPS.toFloat()
                                ),
                                onValueChange = { volumeKeySliderValue = it },
                                onValueChangeFinished = {
                                    setVolumeKeyStepCount(volumeKeySliderValue.roundToInt())
                                },
                                valueRange = VolumeKeyStepPolicy.MIN_STEPS.toFloat()..
                                    VolumeKeyStepPolicy.MAX_STEPS.toFloat(),
                                steps = VolumeKeyStepPolicy.MAX_STEPS - VolumeKeyStepPolicy.MIN_STEPS - 1,
                                showKeyPoints = true,
                                keyPoints = listOf(5f, 15f, 100f),
                                hapticEffect = SliderDefaults.SliderHapticEffect.Step
                            )
                        }
                    )
                    Text(
                        text = stringResource(R.string.volume_key_reboot_required),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
            }
            IntValueDialog(
                show = volumeKeySliderExpanded,
                title = stringResource(R.string.volume_key_step_title),
                summary = stringResource(R.string.volume_key_step_dialog_summary),
                suffix = stringResource(R.string.volume_key_step_suffix),
                range = VolumeKeyStepPolicy.MIN_STEPS..VolumeKeyStepPolicy.MAX_STEPS,
                currentValue = { volumeKeyStepCount },
                emptyValue = volumeKeyStepCount,
                onValueConfirmed = ::setVolumeKeyStepCount,
                onDismissRequest = { volumeKeySliderExpanded = false }
            )

            SmallTitle(stringResource(R.string.aosp_section_system))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = packageInstaller,
                        onCheckedChange = { enabled ->
                            packageInstaller = enabled
                            Preferences.putBoolean(Preferences.KEY_AOSP_PACKAGE_INSTALLER, enabled)
                        },
                        title = stringResource(R.string.aosp_package_installer),
                        summary = stringResource(R.string.aosp_package_installer_summary)
                    )
                }
            }

            SmallTitle(stringResource(R.string.aosp_section_system_ui))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = powerMenu,
                        onCheckedChange = { enabled ->
                            powerMenu = enabled
                            systemUiRestartPending = true
                            requestRestartScopes(RestartScopeSelection(systemUi = true))
                            Preferences.putBoolean(Preferences.KEY_AOSP_POWER_MENU, enabled)
                        },
                        title = stringResource(R.string.aosp_power_menu),
                        summary = stringResource(R.string.aosp_power_menu_summary)
                    )
                    SwitchPreference(
                        checked = volumePanel,
                        onCheckedChange = { enabled ->
                            volumePanel = enabled
                            systemUiRestartPending = true
                            requestRestartScopes(RestartScopeSelection(systemUi = true))
                            Preferences.putBoolean(Preferences.KEY_AOSP_VOLUME_PANEL, enabled)
                        },
                        title = stringResource(R.string.aosp_volume_panel),
                        summary = stringResource(R.string.aosp_volume_panel_summary)
                    )
                    SwitchPreference(
                        checked = volumePanelHapticMiui,
                        onCheckedChange = { enabled ->
                            volumePanelHapticMiui = enabled
                            systemUiRestartPending = true
                            requestRestartScopes(RestartScopeSelection(systemUi = true))
                            Preferences.putBoolean(Preferences.KEY_AOSP_VOLUME_HAPTIC_MIUI, enabled)
                        },
                        enabled = volumePanel,
                        title = stringResource(R.string.aosp_volume_haptic_miui),
                        summary = stringResource(R.string.aosp_volume_haptic_miui_summary)
                    )
                    SwitchPreference(
                        checked = extendUnlockFix,
                        onCheckedChange = { enabled ->
                            extendUnlockFix = enabled
                            systemUiRestartPending = true
                            requestRestartScopes(RestartScopeSelection(systemUi = true))
                            Preferences.putBoolean(Preferences.KEY_EXTEND_UNLOCK_FIX, enabled)
                        },
                        title = stringResource(R.string.aosp_extend_unlock),
                        summary = stringResource(R.string.aosp_extend_unlock_summary)
                    )
                    ArrowPreference(
                        title = stringResource(R.string.aosp_configure_extend_unlock),
                        summary = stringResource(R.string.aosp_configure_extend_unlock_summary),
                        enabled = extendUnlockFix,
                        onClick = { ExtendUnlockLauncher.launch(context) }
                    )
                    SwitchPreference(
                        checked = clipboardEditor,
                        onCheckedChange = { enabled ->
                            clipboardEditor = enabled
                            systemUiRestartPending = true
                            requestRestartScopes(RestartScopeSelection(systemUi = true))
                            Preferences.putBoolean(Preferences.KEY_AOSP_CLIPBOARD_EDITOR, enabled)
                        },
                        title = stringResource(R.string.aosp_clipboard_editor),
                        summary = stringResource(R.string.aosp_clipboard_editor_summary)
                    )
                    if (systemUiRestartPending) {
                        ArrowPreference(
                            title = stringResource(R.string.aosp_restart_system_ui),
                            summary = stringResource(R.string.aosp_restart_system_ui_summary),
                            onClick = {
                                Preferences.flush()
                                RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = RestartScopeSelection(systemUi = true)
                                )
                                handleRestartedScopes(RestartScopeSelection(systemUi = true))
                                systemUiRestartPending = false
                            }
                        )
                    }
                }
            }

            SmallTitle(stringResource(R.string.aosp_section_security_center))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = appInfoEntry,
                        onCheckedChange = { enabled ->
                            appInfoEntry = enabled
                            securityCenterRestartPending = true
                            requestRestartScopes(RestartScopeSelection(securityCenter = true))
                            Preferences.putBoolean(Preferences.KEY_AOSP_APP_INFO_ENTRY, enabled)
                        },
                        title = stringResource(R.string.aosp_app_info_entry),
                        summary = stringResource(R.string.aosp_app_info_entry_summary)
                    )
                    SwitchPreference(
                        checked = appManagerEntry,
                        onCheckedChange = { enabled ->
                            appManagerEntry = enabled
                            securityCenterRestartPending = true
                            requestRestartScopes(RestartScopeSelection(securityCenter = true))
                            Preferences.putBoolean(Preferences.KEY_AOSP_APP_MANAGER_ENTRY, enabled)
                        },
                        title = stringResource(R.string.aosp_app_manager_entry),
                        summary = stringResource(R.string.aosp_app_manager_entry_summary)
                    )
                    if (securityCenterRestartPending) {
                        val restartSelection = RestartScopeSelection(securityCenter = true)
                        ArrowPreference(
                            title = stringResource(R.string.aosp_restart_security_center),
                            summary = stringResource(R.string.aosp_restart_security_center_summary),
                            onClick = {
                                Preferences.flush()
                                RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = restartSelection
                                )
                                handleRestartedScopes(restartSelection)
                                securityCenterRestartPending = false
                            }
                        )
                    }
                }
            }

            SmallTitle(stringResource(R.string.aosp_section_input_method))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.aosp_keyboard_bar),
                    summary = stringResource(R.string.aosp_keyboard_bar_summary),
                    onClick = onNavigateToAospIme
                )
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
