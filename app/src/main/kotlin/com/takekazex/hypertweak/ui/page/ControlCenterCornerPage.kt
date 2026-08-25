package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Control Center corner-radius overrides (Settings → Experimental → 控制中心圆角).
 *
 * State is kept locally like [ChargingDetailPage] and written straight into [Preferences]; the
 * [com.takekazex.hypertweak.hook.rules.slider.ControlCenterCornerHooker] re-reads these Float keys at
 * hook time. The master switch installs the hooks, so it needs a SystemUI restart — every value row
 * marks the restart row pending, exactly like the slider-percentage feature.
 *
 * Each row mirrors the interface-scale interaction from [AppearancePage]: tapping the row expands an
 * inline continuous slider AND opens a numeric value dialog ([CornerRadiusDialog]) for precise
 * entry. The slider runs 0..100 dp continuously with no key-point snapping, so any millimetre
 * precision is reachable; 0 dp means "follow system". Rows are shown in a fixed order.
 */
@Composable
fun ControlCenterCornerPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    var enabled by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CC_CORNER_ENABLED, false))
    }

    // Saveable so a pending restart prompt survives navigating away.
    var restartPending by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    // All corner radius items with their preference keys, in the fixed display order.
    val items = remember {
        listOf(
            CornerItem(R.string.tweaks_cc_corner_slider_title, R.string.tweaks_cc_corner_slider_summary, Preferences.KEY_CC_CORNER_SLIDER),
            CornerItem(R.string.tweaks_cc_corner_tile_title, R.string.tweaks_cc_corner_tile_summary, Preferences.KEY_CC_CORNER_TILE),
            CornerItem(R.string.tweaks_cc_corner_card_title, R.string.tweaks_cc_corner_card_summary, Preferences.KEY_CC_CORNER_CARD),
            CornerItem(R.string.tweaks_cc_corner_device_title, R.string.tweaks_cc_corner_device_summary, Preferences.KEY_CC_CORNER_DEVICE),
            CornerItem(R.string.tweaks_cc_corner_media_title, R.string.tweaks_cc_corner_media_summary, Preferences.KEY_CC_CORNER_MEDIA)
        )
    }

    // Item values stored in a map keyed by preference key.
    val itemValues = remember {
        mutableMapOf<String, Float>().apply {
            items.forEach { item ->
                this[item.key] = Preferences.getFloat(item.key, 0f)
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.tweaks_cc_corner_enabled_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.charging_back))
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

            SmallTitle(stringResource(R.string.tweaks_cc_corner_enabled_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            enabled = checked
                            restartPending = true
                            Preferences.putBoolean(Preferences.KEY_CC_CORNER_ENABLED, checked)
                        },
                        title = stringResource(R.string.tweaks_cc_corner_enabled_title),
                        summary = stringResource(R.string.tweaks_cc_corner_enabled_summary)
                    )
                    if (restartPending) {
                        ArrowPreference(
                            title = stringResource(R.string.charging_restart_title),
                            summary = stringResource(R.string.charging_restart_summary),
                            onClick = {
                                // Push the prefs through to the daemon before SystemUI comes
                                // back — its onHook reads them at plugin load time.
                                com.takekazex.hypertweak.hook.Preferences.flush()
                                com.takekazex.hypertweak.util.RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = com.takekazex.hypertweak.util.RestartScopeSelection(systemUi = true)
                                )
                                restartPending = false
                            }
                        )
                    }
                }
            }

            items.forEach { item ->
                CornerRadiusRow(
                    title = stringResource(item.titleRes),
                    summary = stringResource(item.summaryRes),
                    value = itemValues[item.key] ?: 0f,
                    onValueChange = { newValue ->
                        itemValues[item.key] = newValue
                        Preferences.putFloat(item.key, newValue)
                        restartPending = true
                    }
                )
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

/**
 * One corner-radius group: an expandable row whose inline Slider runs continuously over 0..100 dp
 * (no key-point snapping) and whose tap also opens a numeric [CornerRadiusDialog]. 0 = follow
 * system.
 */
@Composable
private fun CornerRadiusRow(
    title: String,
    summary: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ArrowPreference(
        title = title,
        summary = summary,
        endActions = {
            Text(
                text = cornerRadiusText(value),
                color = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
        },
        onClick = { expanded = !expanded },
        holdDownState = expanded,
        bottomAction = {
            Slider(
                value = value.coerceIn(0f, MAX_CORNER_RADIUS_DP),
                onValueChange = onValueChange,
                onValueChangeFinished = { },
                valueRange = 0f..MAX_CORNER_RADIUS_DP,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step
            )
        }
    )
    // Tapping the row opens the numeric dialog at the same time the inline slider expands,
    // mirroring the interface-scale row in AppearancePage.
    CornerRadiusDialog(
        show = expanded,
        onDismissRequest = { expanded = false },
        current = { value },
        onValueChange = onValueChange
    )
}

@Composable
private fun cornerRadiusText(value: Float): String {
    return if (value <= 0f) {
        stringResource(R.string.tweaks_cc_corner_follow_system)
    } else {
        "${value.toInt()}${stringResource(R.string.tweaks_cc_corner_unit)}"
    }
}

/** Numeric value dialog for one corner-radius row, mirroring [ScaleDialog]. */
@Composable
private fun CornerRadiusDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    current: () -> Float,
    onValueChange: (Float) -> Unit
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.tweaks_cc_corner_dialog_title),
        summary = stringResource(R.string.tweaks_cc_corner_dialog_summary),
        onDismissRequest = onDismissRequest,
        content = {
            var text by remember(show) {
                mutableStateOf(
                    if (current() <= 0f) "" else current().toInt().toString()
                )
            }
            TextField(
                modifier = Modifier.padding(bottom = 16.dp),
                value = text,
                maxLines = 1,
                trailingIcon = {
                    Text(
                        text = stringResource(R.string.tweaks_cc_corner_unit),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                },
                onValueChange = { newValue ->
                    if (newValue.isEmpty()) {
                        text = ""
                    } else {
                        val valid = newValue.all { it.isDigit() }
                        if (valid) {
                            text = newValue
                        }
                    }
                }
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.scale_cancel),
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.scale_ok),
                    onClick = {
                        // Empty field means 0 dp = follow system; any integer is clamped to the
                        // slider range. Empty text therefore maps to 0, not to the current value.
                        val parsed = text.toIntOrNull()
                        val clamped = if (parsed != null) {
                            parsed.coerceIn(0, MAX_CORNER_RADIUS_DP.toInt()).toFloat()
                        } else {
                            0f
                        }
                        onValueChange(clamped)
                        onDismissRequest()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

private const val MAX_CORNER_RADIUS_DP = 100f

private data class CornerItem(
    val titleRes: Int,
    val summaryRes: Int,
    val key: String
)