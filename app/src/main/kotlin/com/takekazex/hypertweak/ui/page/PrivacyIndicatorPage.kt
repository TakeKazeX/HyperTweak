package com.takekazex.hypertweak.ui.page

import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys

import androidx.annotation.StringRes
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.settings.contract.ControlCenterPrivacyPolicy
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlin.math.roundToInt

private val privacyPrefsSaver = Saver<SnapshotStateMap<String, Any>, ArrayList<Any>>(
    save = { state ->
        ArrayList<Any>(state.size * 2).apply {
            state.forEach { (key, value) ->
                add(key)
                add(value)
            }
        }
    },
    restore = { values ->
        mutableStateMapOf<String, Any>().apply {
            values.asSequence().chunked(2).forEach { entry ->
                if (entry.size == 2 && entry[0] is String) put(entry[0] as String, entry[1])
            }
        }
    }
)

@Composable
fun PrivacyIndicatorPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val scrollState = rememberScrollState()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val prefs = rememberSaveable(saver = privacyPrefsSaver) { mutableStateMapOf<String, Any>() }
    fun <T : Any> pref(key: String, default: T): T {
        @Suppress("UNCHECKED_CAST")
        return prefs.getOrPut(key) {
            when (default) {
                is Boolean -> Preferences.getBoolean(key, default)
                is Int -> Preferences.getInt(key, default)
                else -> default
            }
        } as T
    }
    fun changed(key: String, value: Any) {
        prefs[key] = value
        when (value) {
            is Boolean -> Preferences.putBoolean(key, value)
            is Int -> Preferences.putInt(key, value)
        }
        requestRestartScopes(RestartScopeSelection(systemUi = true))
    }
    Scaffold(topBar = {
        TopAppBar(title = stringResource(R.string.settings_privacy_indicator), scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.icon_back)) }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)
            .verticalScroll(scrollState)) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            PrivacyHeaderSection(
                enabled = pref(SystemUiSettingsKeys.KEY_CC_PRIVACY_ENABLED, false),
                centered = pref(SystemUiSettingsKeys.KEY_CC_PRIVACY_CENTER, true),
                background = pref(SystemUiSettingsKeys.KEY_CC_PRIVACY_BACKGROUND, false),
                colored = pref(SystemUiSettingsKeys.KEY_CC_PRIVACY_COLORED, true),
                names = pref(SystemUiSettingsKeys.KEY_CC_PRIVACY_NAMES, true),
                allApps = pref(SystemUiSettingsKeys.KEY_CC_PRIVACY_ALL_APPS, true),
                arrow = pref(SystemUiSettingsKeys.KEY_CC_PRIVACY_ARROW, true),
                dot = pref(SystemUiSettingsKeys.KEY_CC_PRIVACY_DOT, false),
                iconSize = pref(SystemUiSettingsKeys.KEY_CC_PRIVACY_ICON_SIZE, ControlCenterPrivacyPolicy.DEFAULT_ICON_SIZE),
                textSize = pref(SystemUiSettingsKeys.KEY_CC_PRIVACY_TEXT_SIZE, ControlCenterPrivacyPolicy.DEFAULT_TEXT_SIZE),
                offset = pref(SystemUiSettingsKeys.KEY_CC_PRIVACY_OFFSET, ControlCenterPrivacyPolicy.DEFAULT_OFFSET),
                onChange = { key, value -> changed(key, value) }
            )
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
private fun TunerSwitch(
    checked: Boolean,
    title: String,
    summary: String,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onChange,
        title = title,
        summary = summary,
        enabled = enabled
    )
}

@Composable
private fun TunerSwitch(
    checked: Boolean,
    title: String,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onChange,
        title = title,
        enabled = enabled
    )
}

@Composable
private fun PrivacyHeaderSection(
    enabled: Boolean, centered: Boolean, background: Boolean, colored: Boolean,
    names: Boolean, allApps: Boolean, arrow: Boolean, dot: Boolean,
    iconSize: Int, textSize: Int, offset: Int, onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_cc_privacy_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(enabled, stringResource(R.string.icon_cc_privacy_enabled),
                stringResource(R.string.icon_cc_privacy_enabled_summary)) {
                onChange(SystemUiSettingsKeys.KEY_CC_PRIVACY_ENABLED, it)
            }
            if (enabled) {
                ArrowPreference(
                    title = stringResource(R.string.icon_cc_privacy_reset),
                    summary = stringResource(R.string.icon_cc_privacy_reset_summary),
                    onClick = {
                        listOf<Pair<String, Any>>(
                            SystemUiSettingsKeys.KEY_CC_PRIVACY_CENTER to true,
                            SystemUiSettingsKeys.KEY_CC_PRIVACY_BACKGROUND to false,
                            SystemUiSettingsKeys.KEY_CC_PRIVACY_COLORED to true,
                            SystemUiSettingsKeys.KEY_CC_PRIVACY_NAMES to true,
                            SystemUiSettingsKeys.KEY_CC_PRIVACY_ALL_APPS to true,
                            SystemUiSettingsKeys.KEY_CC_PRIVACY_ARROW to true,
                            SystemUiSettingsKeys.KEY_CC_PRIVACY_DOT to false,
                            SystemUiSettingsKeys.KEY_CC_PRIVACY_ICON_SIZE to ControlCenterPrivacyPolicy.DEFAULT_ICON_SIZE,
                            SystemUiSettingsKeys.KEY_CC_PRIVACY_TEXT_SIZE to ControlCenterPrivacyPolicy.DEFAULT_TEXT_SIZE,
                            SystemUiSettingsKeys.KEY_CC_PRIVACY_OFFSET to ControlCenterPrivacyPolicy.DEFAULT_OFFSET
                        ).forEach { (key, value) -> onChange(key, value) }
                    }
                )
                TunerSwitch(centered, stringResource(R.string.icon_cc_privacy_center)) {
                    onChange(SystemUiSettingsKeys.KEY_CC_PRIVACY_CENTER, it)
                }
                TunerSwitch(background, stringResource(R.string.icon_cc_privacy_background)) {
                    onChange(SystemUiSettingsKeys.KEY_CC_PRIVACY_BACKGROUND, it)
                }
                TunerSwitch(colored, stringResource(R.string.icon_cc_privacy_colored)) {
                    onChange(SystemUiSettingsKeys.KEY_CC_PRIVACY_COLORED, it)
                }
                TunerSwitch(names, stringResource(R.string.icon_cc_privacy_names)) {
                    onChange(SystemUiSettingsKeys.KEY_CC_PRIVACY_NAMES, it)
                }
                TunerSwitch(allApps, stringResource(R.string.icon_cc_privacy_all_apps), enabled = names) {
                    onChange(SystemUiSettingsKeys.KEY_CC_PRIVACY_ALL_APPS, it)
                }
                TunerSwitch(arrow, stringResource(R.string.icon_cc_privacy_arrow)) {
                    onChange(SystemUiSettingsKeys.KEY_CC_PRIVACY_ARROW, it)
                }
                TunerSwitch(dot, stringResource(R.string.icon_cc_privacy_dot)) {
                    onChange(SystemUiSettingsKeys.KEY_CC_PRIVACY_DOT, it)
                }
                PrivacySizeRow(R.string.icon_cc_privacy_icon_size, iconSize, 16..32, "dp") {
                    onChange(SystemUiSettingsKeys.KEY_CC_PRIVACY_ICON_SIZE, it)
                }
                PrivacySizeRow(R.string.icon_cc_privacy_text_size, textSize, 9..20, "sp") {
                    onChange(SystemUiSettingsKeys.KEY_CC_PRIVACY_TEXT_SIZE, it)
                }
                PrivacySizeRow(R.string.icon_cc_privacy_offset, offset, -16..24, "dp") {
                    onChange(SystemUiSettingsKeys.KEY_CC_PRIVACY_OFFSET, it)
                }
            }
        }
    }
}

@Composable
private fun PrivacySizeRow(@StringRes title: Int, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.coerceIn(range).toFloat()) }
    var showDialog by rememberSaveable { mutableStateOf(false) }
    ArrowPreference(
        title = stringResource(title),
        endActions = { Text("${sliderValue.roundToInt()} $unit", color = MiuixTheme.colorScheme.onSurfaceVariantActions) },
        onClick = { showDialog = true },
        bottomAction = {
            Slider(
                value = sliderValue.coerceIn(range.first.toFloat(), range.last.toFloat()),
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    val snapped = sliderValue.roundToInt().coerceIn(range)
                    sliderValue = snapped.toFloat()
                    onChange(snapped)
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = 0,
                showKeyPoints = false
            )
        }
    )
    IntValueDialog(
        show = showDialog,
        title = stringResource(title),
        summary = stringResource(R.string.privacy_value_range, range.first, range.last, unit),
        suffix = unit,
        range = range,
        currentValue = { sliderValue.roundToInt() },
        emptyValue = value,
        onValueConfirmed = { sliderValue = it.toFloat(); onChange(it) },
        onDismissRequest = { showDialog = false }
    )
}
