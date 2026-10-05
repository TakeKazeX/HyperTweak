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
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.RestartUtils
import kotlin.math.roundToInt
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
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Power-save overrides and battery automation, with restart scopes tracked independently. */
@Composable
fun PowerSavePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current
    val securityCenterScope = remember { RestartScopeSelection(securityCenter = true) }
    val systemUiScope = remember { RestartScopeSelection(systemUi = true) }

    var refreshRate by remember { mutableStateOf(Preferences.keepRefreshRateInPowerSave()) }
    var hapticFeedback by remember { mutableStateOf(Preferences.keepHapticFeedbackInPowerSave()) }
    var systemSounds by remember { mutableStateOf(Preferences.keepSystemSoundsInPowerSave()) }
    var wakeupGestures by remember { mutableStateOf(Preferences.keepWakeupGesturesInPowerSave()) }
    var autoPowerSave by remember { mutableStateOf(Preferences.batteryAutoPowerSaveEnabled()) }
    var autoThreshold by remember {
        mutableFloatStateOf(Preferences.batteryAutoPowerSaveThreshold().toFloat())
    }
    var exitWhenCharging by remember { mutableStateOf(Preferences.exitPowerSaveWhenCharging()) }

    var securityRestartPending by rememberSaveable { mutableStateOf(false) }
    var systemUiRestartPending by rememberSaveable { mutableStateOf(false) }
    val restartScopes = RestartScopeSelection(
        securityCenter = securityRestartPending,
        systemUi = systemUiRestartPending,
    )

    fun requestRestart(scope: RestartScopeSelection) {
        securityRestartPending = securityRestartPending || scope.securityCenter
        systemUiRestartPending = systemUiRestartPending || scope.systemUi
        requestRestartScopes(scope)
    }

    fun setOverride(key: String, value: Boolean, scope: RestartScopeSelection) {
        if (scope.securityCenter) Preferences.setPowerSaveFeature(key, value)
        else Preferences.putBoolean(key, value)
        requestRestart(scope)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.power_save_override_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.power_save_override_back))
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
            // The top bar is transparent and content scrolls beneath it, so the first item has to
            // clear its height explicitly (same as the other second-level pages).
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))

            SmallTitle(stringResource(R.string.power_save_override_section_features))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = refreshRate,
                        onCheckedChange = { checked ->
                            refreshRate = checked
                            setOverride(
                                Preferences.KEY_KEEP_REFRESH_RATE_IN_POWER_SAVE,
                                checked,
                                securityCenterScope,
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
                                Preferences.KEY_KEEP_HAPTIC_FEEDBACK_IN_POWER_SAVE,
                                checked,
                                securityCenterScope,
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
                                Preferences.KEY_KEEP_SYSTEM_SOUNDS_IN_POWER_SAVE,
                                checked,
                                securityCenterScope,
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
                                Preferences.KEY_KEEP_WAKEUP_GESTURES_IN_POWER_SAVE,
                                checked,
                                securityCenterScope,
                            )
                        },
                        title = stringResource(R.string.power_save_override_wakeup_title),
                        summary = stringResource(R.string.power_save_override_wakeup_summary)
                    )
                }
            }

            SmallTitle(stringResource(R.string.power_save_override_section_auto))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = autoPowerSave,
                        onCheckedChange = { checked ->
                            autoPowerSave = checked
                            setOverride(
                                Preferences.KEY_BATTERY_AUTO_POWER_SAVE_ENABLED,
                                checked,
                                systemUiScope,
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
                            Preferences.putInt(
                                Preferences.KEY_BATTERY_AUTO_POWER_SAVE_THRESHOLD,
                                autoThreshold.roundToInt(),
                            )
                            requestRestart(systemUiScope)
                        },
                    )
                    SwitchPreference(
                        checked = exitWhenCharging,
                        onCheckedChange = { checked ->
                            exitWhenCharging = checked
                            setOverride(
                                Preferences.KEY_EXIT_POWER_SAVE_WHEN_CHARGING,
                                checked,
                                systemUiScope,
                            )
                        },
                        title = stringResource(R.string.power_save_exit_charging_title),
                        summary = stringResource(R.string.power_save_exit_charging_summary)
                    )
                }
            }

            if (!restartScopes.isEmpty()) {
                SmallTitle(stringResource(R.string.power_save_override_section_apply))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.power_save_override_restart_title),
                        summary = listOfNotNull(
                            stringResource(R.string.restart_scope_security).takeIf { securityRestartPending },
                            stringResource(R.string.restart_scope_system_ui).takeIf { systemUiRestartPending },
                        ).joinToString(", "),
                        onClick = {
                            Preferences.flush()
                            val selection = restartScopes
                            RestartUtils.restartScope(
                                context = context,
                                coroutineScope = coroutineScope,
                                selection = selection,
                            )
                            handleRestartedScopes(selection)
                            securityRestartPending = false
                            systemUiRestartPending = false
                        }
                    )
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
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
    val range = Preferences.MIN_BATTERY_AUTO_POWER_SAVE_THRESHOLD..
        Preferences.MAX_BATTERY_AUTO_POWER_SAVE_THRESHOLD
    val current = { value.roundToInt().coerceIn(range.first, range.last) }
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
                value = value.coerceIn(range.first.toFloat(), range.last.toFloat()),
                onValueChange = onValueChange,
                onValueChangeFinished = onValueSettled,
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
