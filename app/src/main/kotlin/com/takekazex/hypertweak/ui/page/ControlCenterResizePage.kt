package com.takekazex.hypertweak.ui.page

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
import androidx.compose.runtime.mutableStateMapOf
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Control Center element sizes (Settings → Experimental → 控制中心尺寸).
 *
 * Self-contained state like [ControlCenterCornerPage]: every control reads/writes [Preferences]
 * directly and the hooker re-reads the keys live on each bind. The master switch installs the
 * hooks, so it needs one SystemUI restart (pending-restart row below); individual sizes apply on
 * the next panel refresh without one.
 *
 * Sizes are stored as `C x R` tokens (columns × rows); empty = follow the system. Big-card sizes
 * share one `spec=CxR` map key; sliders/media/device center have one token each. Dropdown labels
 * spell out 行×列 explicitly so the axis order is never ambiguous.
 */
@Composable
fun ControlCenterResizePage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var enabled by remember {
        mutableStateOf(Preferences.getBoolean(Preferences.KEY_CC_RESIZE_ENABLED, false))
    }
    var restartPending by rememberSaveable { mutableStateOf(false) }

    fun markPending() {
        restartPending = true
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.cc_resize_title),
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

            SmallTitle(stringResource(R.string.cc_resize_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            enabled = checked
                            markPending()
                            Preferences.putBoolean(Preferences.KEY_CC_RESIZE_ENABLED, checked)
                        },
                        title = stringResource(R.string.cc_resize_title),
                        summary = stringResource(R.string.cc_resize_enabled_summary)
                    )
                    if (restartPending) {
                        ArrowPreference(
                            title = stringResource(R.string.charging_restart_title),
                            summary = stringResource(R.string.charging_restart_summary),
                            onClick = {
                                Preferences.flush()
                                RestartUtils.restartScope(
                                    context = context,
                                    coroutineScope = coroutineScope,
                                    selection = RestartScopeSelection(systemUi = true)
                                )
                                restartPending = false
                            }
                        )
                    }
                }
            }

            // ─── Big cards (WiFi / cellular / BT / VoWiFi) ───
            SmallTitle(stringResource(R.string.cc_size_section_cards))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    CardSpecRow.entries.forEach { entry ->
                        CardSizeRow(
                            title = stringResource(entry.titleRes),
                            current = cardSizeFor(entry.spec),
                            options = CARD_SIZE_OPTIONS,
                            onSelect = { value ->
                                setCardSize(entry.spec, value)
                                markPending()
                            }
                        )
                    }
                }
            }

            // ─── Sliders ───
            SmallTitle(stringResource(R.string.cc_size_section_sliders))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    TokenSizeRow(
                        title = stringResource(R.string.cc_size_brightness),
                        summary = stringResource(R.string.cc_size_brightness_summary),
                        key = Preferences.KEY_CC_SIZE_BRIGHTNESS,
                        options = SLIDER_SIZE_OPTIONS,
                        defaults = SizeOption(1, 2),
                        onSelect = { markPending() }
                    )
                    TokenSizeRow(
                        title = stringResource(R.string.cc_size_volume),
                        summary = stringResource(R.string.cc_size_volume_summary),
                        key = Preferences.KEY_CC_SIZE_VOLUME,
                        options = SLIDER_SIZE_OPTIONS,
                        defaults = SizeOption(1, 2),
                        onSelect = { markPending() }
                    )
                }
            }

            // ─── Media player / device center ───
            SmallTitle(stringResource(R.string.cc_size_section_other))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    TokenSizeRow(
                        title = stringResource(R.string.cc_size_media),
                        summary = stringResource(R.string.cc_size_media_summary),
                        key = Preferences.KEY_CC_SIZE_MEDIA,
                        options = MEDIA_SIZE_OPTIONS,
                        defaults = SizeOption(2, 2),
                        onSelect = { markPending() }
                    )
                    TokenSizeRow(
                        title = stringResource(R.string.cc_size_device),
                        summary = stringResource(R.string.cc_size_device_summary),
                        key = Preferences.KEY_CC_SIZE_DEVICE,
                        options = DEVICE_SIZE_OPTIONS,
                        defaults = SizeOption(4, 1),
                        onSelect = { markPending() }
                    )
                }
            }

            // ─── Quick switches rendered as big cards ───
            SmallTitle(stringResource(R.string.cc_tile_specs_section))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.cc_tile_specs_section_summary),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                    KnownTileSpec.entries.forEach { entry ->
                        var checked by remember(entry.spec) {
                            mutableStateOf(entry.spec in readTileCardSpecs())
                        }
                        SwitchPreference(
                            checked = checked,
                            onCheckedChange = { newValue ->
                                checked = newValue
                                writeTileCardSpecs(entry.spec, newValue)
                                markPending()
                            },
                            title = stringResource(entry.titleRes)
                        )
                    }
                    CustomTileSpecRow(onSelect = { markPending() })
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

// ─── Size model ────────────────────────────────────────────────────────────────

/** One selectable size preset: columns × rows, empty token = follow the system. */
private data class SizeOption(val cols: Int, val rows: Int) {
    val token: String get() = "${cols}x${rows}"
}

private val FOLLOW_SYSTEM = SizeOption(0, 0)

// Cards stock at 2 cols × 1 row; single-cell and taller variants cover the requested presets.
private val CARD_SIZE_OPTIONS = listOf(FOLLOW_SYSTEM, SizeOption(1, 1), SizeOption(2, 1), SizeOption(1, 2), SizeOption(2, 2))

// Sliders stock at 1 col × 2 rows.
private val SLIDER_SIZE_OPTIONS = listOf(FOLLOW_SYSTEM, SizeOption(1, 1), SizeOption(1, 2), SizeOption(2, 1), SizeOption(2, 2))

// Media player stocks at 2×2; the requested banner/tall/full-width variants.
private val MEDIA_SIZE_OPTIONS = listOf(FOLLOW_SYSTEM, SizeOption(2, 2), SizeOption(3, 1), SizeOption(3, 2), SizeOption(2, 3), SizeOption(4, 2))

// Device center stocks at full width (4 cols × 1 row).
private val DEVICE_SIZE_OPTIONS = listOf(FOLLOW_SYSTEM, SizeOption(4, 1), SizeOption(3, 1), SizeOption(2, 1))

/** The per-card size map (`spec=CxR`) kept in memory and mirrored into [Preferences]. */
private val cardSizesState = mutableStateMapOf<String, String>()

private fun cardSizeFor(spec: String): String {
    if (!cardSizesState.containsKey("__loaded__")) {
        cardSizesState.clear()
        Preferences.getString(Preferences.KEY_CC_CARD_SIZES, "")
            .split(',')
            .mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx <= 0) null else entry.substring(0, idx).trim().lowercase() to
                    entry.substring(idx + 1).trim()
            }
            .forEach { (spec, value) -> cardSizesState[spec] = value }
        cardSizesState["__loaded__"] = "1"
    }
    return cardSizesState[spec] ?: ""
}

private fun setCardSize(spec: String, value: String) {
    cardSizeFor(spec) // ensure loaded
    if (value.isEmpty()) cardSizesState.remove(spec) else cardSizesState[spec] = value
    val raw = cardSizesState.entries
        .filter { it.key != "__loaded__" && it.value.isNotBlank() }
        .joinToString(",") { "${it.key}=${it.value}" }
    Preferences.putString(Preferences.KEY_CC_CARD_SIZES, raw)
}

private enum class CardSpecRow(val spec: String, val titleRes: Int) {
    WIFI("wifi", R.string.cc_size_card_wifi),
    CELL("cell", R.string.cc_size_card_cell),
    BT("bt", R.string.cc_size_card_bt),
    VOWIFI1("vowifi1", R.string.cc_size_card_vowifi1),
    VOWIFI2("vowifi2", R.string.cc_size_card_vowifi2)
}

// ─── Rows ──────────────────────────────────────────────────────────────────────

/** Label like 跟随系统 / 默认（2行×1列）/ 2行×1列. */
@Composable
private fun sizeLabel(option: SizeOption, isDefault: Boolean): String = when {
    option.cols == 0 -> stringResource(R.string.cc_size_follow_system)
    else -> {
        val base = "${option.rows}${stringResource(R.string.cc_unit_rows)}" +
            "×${option.cols}${stringResource(R.string.cc_unit_cols)}"
        if (isDefault) stringResource(R.string.cc_size_default_label, base) else base
    }
}

@Composable
private fun CardSizeRow(
    title: String,
    current: String,
    options: List<SizeOption>,
    onSelect: (String) -> Unit
) {
    val default = SizeOption(2, 1)
    OverlayDropdownPreference(
        title = title,
        summary = stringResource(R.string.cc_size_card_summary),
        items = options.map { sizeLabel(it, it == default) },
        selectedIndex = dropdownIndex(options, current),
        onSelectedIndexChange = { index -> onSelect(options[index].token) }
    )
}

@Composable
private fun TokenSizeRow(
    title: String,
    summary: String,
    key: String,
    options: List<SizeOption>,
    defaults: SizeOption,
    onSelect: () -> Unit
) {
    var current by remember(key) { mutableStateOf(Preferences.getString(key, "")) }
    OverlayDropdownPreference(
        title = title,
        summary = summary,
        items = options.map { sizeLabel(it, it == defaults) },
        selectedIndex = dropdownIndex(options, current),
        onSelectedIndexChange = { index ->
            val value = options[index].token
            current = value
            Preferences.putString(key, value)
            onSelect()
        }
    )
}

private fun dropdownIndex(options: List<SizeOption>, current: String): Int =
    options.indexOfFirst { it.token == current }.takeIf { it >= 0 }
        ?: options.indexOfFirst { it == FOLLOW_SYSTEM }

// ─── Quick-switch tile specs ───────────────────────────────────────────────────

private enum class KnownTileSpec(val spec: String, val titleRes: Int) {
    BT("bt", R.string.cc_tile_bt),
    FLASHLIGHT("flashlight", R.string.cc_tile_flashlight),
    MUTE("mute", R.string.cc_tile_mute),
    AIRPLANE("airplane", R.string.cc_tile_airplane),
    ROTATION("rotation", R.string.cc_tile_rotation),
    BATTERY_SAVER("batterysaver", R.string.cc_tile_batterysaver),
    NIGHT("night", R.string.cc_tile_night),
    HOTSPOT("hotspot", R.string.cc_tile_hotspot),
    SCREENCAST("screencast", R.string.cc_tile_screencast),
    PAPER_MODE("papermode", R.string.cc_tile_papermode),
    QUIET_MODE("quietmode", R.string.cc_tile_quietmode),
    AUTO_BRIGHTNESS("autobrightness", R.string.cc_tile_autobrightness),
    COOLING_FAN("coolingfan", R.string.cc_tile_coolingfan),
    VOWIFI1("vowifi1", R.string.cc_size_card_vowifi1),
    VOWIFI2("vowifi2", R.string.cc_size_card_vowifi2)
}

private val knownSpecTokens = KnownTileSpec.entries.map { it.spec }.toSet()

private fun readTileCardSpecs(): Set<String> =
    Preferences.getString(Preferences.KEY_CC_TILE_CARD_SPECS, "")
        .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

/** Rewrites the spec key keeping unknown (hand-typed) specs intact. */
private fun writeTileCardSpecs(spec: String, add: Boolean) {
    val known = readTileCardSpecs().toMutableSet()
    if (add) known += spec else known -= spec
    val customs = readTileCardSpecs().filterNot { it in knownSpecTokens }
    Preferences.putString(Preferences.KEY_CC_TILE_CARD_SPECS, (known + customs).joinToString(","))
}

@Composable
private fun CustomTileSpecRow(onSelect: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ArrowPreference(
        title = stringResource(R.string.cc_tile_custom_title),
        summary = stringResource(R.string.cc_tile_custom_summary),
        onClick = { expanded = true }
    )
    OverlayDialog(
        show = expanded,
        title = stringResource(R.string.cc_tile_custom_title),
        summary = stringResource(R.string.cc_tile_custom_dialog_summary),
        onDismissRequest = { expanded = false },
        content = {
            var text by remember(expanded) {
                mutableStateOf(
                    readTileCardSpecs().filterNot { it in knownSpecTokens }.joinToString(",")
                )
            }
            TextField(
                modifier = Modifier.padding(bottom = 16.dp),
                value = text,
                maxLines = 1,
                onValueChange = { text = it.filter { c -> !c.isWhitespace() } }
            )
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.scale_cancel),
                    onClick = { expanded = false },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.scale_ok),
                    onClick = {
                        val known = readTileCardSpecs().filter { it in knownSpecTokens }.toSet()
                        val customs = text.split(',').map { it.trim().lowercase() }
                            .filter { it.isNotEmpty() }
                        Preferences.putString(
                            Preferences.KEY_CC_TILE_CARD_SPECS, (known + customs).joinToString(",")
                        )
                        onSelect()
                        expanded = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}
