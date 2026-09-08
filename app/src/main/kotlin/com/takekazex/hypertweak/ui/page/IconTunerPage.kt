package com.takekazex.hypertweak.ui.page

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicy
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSlotPolicyConfig
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSvgRenderConfig
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconSvgRepository
import com.takekazex.hypertweak.hook.rules.systemui.icon.SvgKind
import com.takekazex.hypertweak.hook.rules.systemui.icon.IconTunerOptions
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.RestartUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
private val iconPrefsSaver = Saver<SnapshotStateMap<String, Any>, ArrayList<Any>>(
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

/**
 * Status-bar icon customization, ported from Hyper Helper's icon tuner
 * (see the reverse-engineering workspace, cache/xiaomihelper-2bfd4873a4138764, and
 * OS4_ADAPTATION_PLAN.md). State is kept locally rather than hoisted into `MainActivity`, so every
 * change reports SystemUI to the shared Home restart dialog; the page also keeps its local restart
 * affordance. The page is a second-level settings menu: related controls are
 * grouped behind a compact TabRow so the long list does not become one undifferentiated scroll.
 */

@Composable
private fun TunerSwitch(
    checked: Boolean,
    title: String,
    summary: String,
    onChange: (Boolean) -> Unit
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onChange,
        title = title,
        summary = summary
    )
}

@Composable
private fun TunerSwitch(
    checked: Boolean,
    title: String,
    onChange: (Boolean) -> Unit
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onChange,
        title = title
    )
}

@Composable
fun IconTunerPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current
    var selectedCategory by rememberSaveable { mutableIntStateOf(0) }
    var systemUiRestartPending by rememberSaveable { mutableStateOf(false) }
    var stackedSingleImportStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var stackedSinglePreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var stackedImportStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var stackedPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    val iconSvgRepository = remember(context) { IconSvgRepository(context.applicationContext) }

    // Local remember-backed state so toggles update instantly; the remote preference write is
    // async, so reading Preferences directly in composition made switches bounce back.
    val prefs = rememberSaveable(saver = iconPrefsSaver) {
        mutableStateMapOf<String, Any>()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> pref(key: String, default: T): T {
        val existing = prefs[key]
        if (existing != null) {
            val restored = if (default is Set<*> && existing is List<*>) {
                existing.filterIsInstance<String>().toSet()
            } else {
                existing
            }
            return restored as T
        }
        // Load the stored value once; defaults are only for keys never written.
        val stored: T = when (default) {
            is Boolean -> Preferences.getBoolean(key, default as Boolean) as T
            is Int -> Preferences.getInt(key, default as Int) as T
            is Float -> Preferences.getFloat(key, default as Float) as T
            is String -> Preferences.getString(key, default as String) as T
            is Set<*> -> Preferences.getStringSet(
                key,
                default.filterIsInstance<String>().toSet()
            ) as T
            else -> default
        }
        prefs[key] = if (stored is Set<*>) {
            ArrayList(stored.filterIsInstance<String>())
        } else {
            stored as Any
        }
        return stored
    }

    fun changed(key: String, value: Any) {
        requestRestartScopes(RestartScopeSelection(systemUi = true))
        prefs[key] = if (value is Set<*>) {
            ArrayList(value.filterIsInstance<String>())
        } else {
            value
        }
        when (value) {
            is Boolean -> Preferences.putBoolean(key, value)
            is Int -> Preferences.putInt(key, value)
            is Float -> Preferences.putFloat(key, value)
            is String -> Preferences.putString(key, value)
            is Set<*> -> Preferences.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
        systemUiRestartPending = true
    }

    val signalImportInProgressText = stringResource(R.string.icon_stacked_signal_importing)
    val signalImportSuccessTemplate = stringResource(R.string.icon_stacked_signal_import_success)
    val signalImportFailedText = stringResource(R.string.icon_stacked_signal_import_failed)

    val importSingleSignalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        stackedSingleImportStatus = signalImportInProgressText
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                iconSvgRepository.importFromUri(uri, SvgKind.SINGLE_SIGNAL)
            }
            result.fold(
                onSuccess = { snapshot ->
                    stackedSinglePreview = withContext(Dispatchers.Default) {
                        iconSvgRepository.preview(
                            snapshot,
                            levelA = 3,
                            config = IconSvgRenderConfig(iconHeightPx = 48)
                        ).getOrNull()?.asImageBitmap()
                    }
                    changed(Preferences.KEY_ICON_STACKED_SVG_SINGLE, 2)
                    stackedSingleImportStatus = String.format(
                        Locale.getDefault(),
                        signalImportSuccessTemplate,
                        snapshot.displayName
                    )
                },
                onFailure = { failure ->
                    DebugLog.w("IconTuner", "single signal SVG import failed", failure)
                    stackedSingleImportStatus = signalImportFailedText
                }
            )
        }
    }

    val importStackedSignalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        stackedImportStatus = signalImportInProgressText
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                iconSvgRepository.importFromUri(uri, SvgKind.STACKED_SIGNAL)
            }
            result.fold(
                onSuccess = { snapshot ->
                    stackedPreview = withContext(Dispatchers.Default) {
                        iconSvgRepository.preview(
                            snapshot,
                            levelA = 3,
                            levelB = 2,
                            config = IconSvgRenderConfig(iconHeightPx = 48)
                        ).getOrNull()?.asImageBitmap()
                    }
                    changed(Preferences.KEY_ICON_STACKED_SVG_STACKED, 2)
                    stackedImportStatus = String.format(
                        Locale.getDefault(),
                        signalImportSuccessTemplate,
                        snapshot.displayName
                    )
                },
                onFailure = { failure ->
                    DebugLog.w("IconTuner", "stacked signal SVG import failed", failure)
                    stackedImportStatus = signalImportFailedText
                }
            )
        }
    }

    // Modes follow Hyper Helper: 0 = follow system (lists untouched), 1 = visible everywhere,
    // 2 = status bar only, 3 = control center only, 4 = hidden everywhere.
    val slotModes = listOf(
        stringResource(R.string.icon_slot_mode_follow),
        stringResource(R.string.icon_slot_mode_visible),
        stringResource(R.string.icon_slot_mode_status_bar),
        stringResource(R.string.icon_slot_mode_control_center),
        stringResource(R.string.icon_slot_mode_hidden)
    )
    val commonSlots = listOf(
        "mobile", "no_sim", "airplane", "wifi", "demo_wifi", "hotspot", "vpn",
        "network_speed", "bluetooth", "bluetooth_handsfree_battery", "handle_battery",
        "nfc", "gps", "location", "wireless_headset", "phone", "pad", "pc",
        "sound_box_group", "stereo", "sound_box_screen", "sound_box", "tv", "glasses",
        "car", "camera", "dist_compute", "headset", "alarm_clock", "zen", "volume",
        "second_space", "compound_icon"
    ) + IconSlotPolicy.MODULE_SLOTS

    val legacyLeftEnabled = pref(Preferences.KEY_ICON_LEFT_CONTAINER_ENABLED, false)
    val leftMode = pref(
        IconTunerOptions.KEY_LEFT_MODE,
        if (Preferences.contains(IconTunerOptions.KEY_LEFT_MODE)) {
            Preferences.getInt(IconTunerOptions.KEY_LEFT_MODE, IconTunerOptions.LEFT_MODE_DISABLED)
        } else if (legacyLeftEnabled) {
            IconTunerOptions.LEFT_MODE_HOME
        } else {
            IconTunerOptions.LEFT_MODE_DISABLED
        }
    )

    val categories = listOf(
        stringResource(R.string.icon_tuner_tab_layout),
        stringResource(R.string.icon_tuner_tab_mobile),
        stringResource(R.string.icon_tuner_tab_wifi),
        stringResource(R.string.icon_tuner_tab_other)
    )
    // The page backdrop is already Miuix `surface`, which is also TabRowWithContour's default
    // outer color. Use the container surface for the contour so the tab bar keeps a visible base.
    val tabRowColors = TabRowDefaults.tabRowColors(
        backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
        selectedBackgroundColor = MiuixTheme.colorScheme.surface
    )

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.icon_page_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.icon_back)) } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding()))
            TabRowWithContour(
                tabs = categories,
                selectedTabIndex = selectedCategory.coerceIn(0, categories.lastIndex),
                onTabSelected = { selectedCategory = it },
                colors = tabRowColors,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Column(
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .verticalScroll(remember(selectedCategory) { ScrollState(0) })
            ) {
                Spacer(Modifier.height(8.dp))

                when (selectedCategory.coerceIn(0, categories.lastIndex)) {
                    0 -> {
                        PositionSection(
                            position = pref(IconTunerOptions.KEY_POSITION, 0),
                            customOrder = pref(IconTunerOptions.KEY_POSITION_VALUES, emptySet<String>()),
                            reorderHidden = pref(IconTunerOptions.KEY_POSITION_REORDER, false),
                            extraHidden = pref(Preferences.KEY_ICON_EXT_BLOCKED, ""),
                            onChange = { key, value -> changed(key, value) }
                        )
                        LeftContainerSection(
                            mode = leftMode,
                            zen = pref(Preferences.KEY_ICON_LEFT_ZEN, false),
                            volume = pref(Preferences.KEY_ICON_LEFT_VOLUME, false),
                            hotspot = pref(Preferences.KEY_ICON_LEFT_HOTSPOT, false),
                            alarmClock = pref(Preferences.KEY_ICON_LEFT_ALARM_CLOCK, false),
                            location = pref(Preferences.KEY_ICON_LEFT_LOCATION, false),
                            bluetooth = pref(Preferences.KEY_ICON_LEFT_BLUETOOTH, false),
                            nfc = pref(Preferences.KEY_ICON_LEFT_NFC, false),
                            vpn = pref(Preferences.KEY_ICON_LEFT_VPN, false),
                            airplane = pref(Preferences.KEY_ICON_LEFT_AIRPLANE, false),
                            headset = pref(Preferences.KEY_ICON_LEFT_HEADSET, false),
                            compound = pref(Preferences.KEY_ICON_LEFT_COMPOUND, false),
                            onChange = { key, value ->
                                changed(key, value)
                                if (key == IconTunerOptions.KEY_LEFT_MODE && value is Int) {
                                    changed(Preferences.KEY_ICON_LEFT_CONTAINER_ENABLED, value != 0)
                                }
                            }
                        )
                    }
                    1 -> {
                        StackedSignalSection(
                enabled = pref(Preferences.KEY_ICON_STACKED_ENABLED, false),
                scale = pref(Preferences.KEY_ICON_STACKED_SCALE, 1f),
                singleStyle = pref(Preferences.KEY_ICON_STACKED_SVG_SINGLE, 0),
                stackedStyle = pref(Preferences.KEY_ICON_STACKED_SVG_STACKED, 0),
                signalAlphaFg = pref(Preferences.KEY_ICON_STACKED_ALPHA_FG, 1f),
                signalAlphaBg = pref(Preferences.KEY_ICON_STACKED_ALPHA_BG, 0.4f),
                signalAlphaError = pref(Preferences.KEY_ICON_STACKED_ALPHA_ERROR, 0.2f),
                signalPaddingStart = pref(Preferences.KEY_ICON_STACKED_PADDING_START, 0f),
                signalPaddingEnd = pref(Preferences.KEY_ICON_STACKED_PADDING_END, 0f),
                hideWhenDisconnected = pref(Preferences.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT, false),
                hideWhenWifi = pref(Preferences.KEY_ICON_STACKED_TYPE_HIDE_WIFI, false),
                showSingleBadge = pref(Preferences.KEY_ICON_STACKED_TYPE_SHOW_SINGLE, false),
                showStackedBadge = pref(Preferences.KEY_ICON_STACKED_TYPE_SHOW_STACKED, false),
                showRoaming = pref(Preferences.KEY_ICON_STACKED_TYPE_ROAMING, false),
                typeSize = pref(Preferences.KEY_ICON_STACKED_TYPE_SIZE, 14f),
                badgeSize = pref(Preferences.KEY_ICON_STACKED_TYPE_BADGE_SIZE, 7.16f),
                width = pref(Preferences.KEY_ICON_STACKED_TYPE_WIDTH_CONDENSED, 80),
                weight = pref(Preferences.KEY_ICON_STACKED_TYPE_WEIGHT, 630),
                singleWeight = pref(Preferences.KEY_ICON_STACKED_TYPE_SINGLE_WEIGHT, 400),
                paddingStart = pref(Preferences.KEY_ICON_STACKED_TYPE_PADDING_START, 2f),
                paddingEnd = pref(Preferences.KEY_ICON_STACKED_TYPE_PADDING_END, 2f),
                verticalOffset = pref(Preferences.KEY_ICON_STACKED_TYPE_VERTICAL_OFFSET, 0f),
                fontMode = pref(Preferences.KEY_ICON_STACKED_TYPE_FONT, 0),
                singleImportStatus = stackedSingleImportStatus,
                stackedImportStatus = stackedImportStatus,
                singlePreview = stackedSinglePreview,
                stackedPreview = stackedPreview,
                onImportSingle = {
                    importSingleSignalLauncher.launch(arrayOf("image/svg+xml", "text/xml", "text/plain"))
                },
                onImportStacked = {
                    importStackedSignalLauncher.launch(arrayOf("image/svg+xml", "text/xml", "text/plain"))
                },
                onChange = { key, value ->
                    if (value is Int && value != 2) {
                        when (key) {
                            Preferences.KEY_ICON_STACKED_SVG_SINGLE -> {
                                stackedSinglePreview = null
                                stackedSingleImportStatus = null
                            }
                            Preferences.KEY_ICON_STACKED_SVG_STACKED -> {
                                stackedPreview = null
                                stackedImportStatus = null
                            }
                        }
                    }
                    changed(key, value)
                }
                        )
                        CellularSection(
                activity = pref(Preferences.KEY_ICON_HIDE_CELLULAR_ACTIVITY, false),
                type = pref(Preferences.KEY_ICON_HIDE_CELLULAR_TYPE, false),
                roam = pref(Preferences.KEY_ICON_HIDE_CELLULAR_ROAM, false),
                smallRoam = pref(Preferences.KEY_ICON_HIDE_CELLULAR_SMALL_ROAM, false),
                roamGlobal = pref(Preferences.KEY_ICON_HIDE_CELLULAR_ROAM_GLOBAL, false),
                voWifi = pref(Preferences.KEY_ICON_HIDE_CELLULAR_VOWIFI, false),
                volte = pref(Preferences.KEY_ICON_HIDE_CELLULAR_VOLTE, false),
                volteNoService = pref(Preferences.KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE, false),
                speechHd = pref(Preferences.KEY_ICON_HIDE_CELLULAR_SPEECH_HD, false),
                hideNonDefaultSim = pref(Preferences.KEY_ICON_HIDE_NON_DEFAULT_SIM, false),
                hideSimOne = pref(Preferences.KEY_ICON_HIDE_SIM_ONE, false),
                hideSimTwo = pref(Preferences.KEY_ICON_HIDE_SIM_TWO, false),
                onChange = { key, value ->
                    changed(key, value)
                }
                        )
                        CellularTypeSection(
                forceSingle = pref(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE, false),
                useCustom = pref(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM, false),
                customValue = pref(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL, ""),
                swapSingle = pref(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SWAP, false),
                singleSizeEnabled = pref(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SIZE, false),
                singleSize = pref(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SIZE_VAL, 14f),
                drawableFontEnabled = pref(Preferences.KEY_ICON_FONT_MOBILE_TYPE, false),
                drawableWeight = pref(Preferences.KEY_ICON_FONT_MOBILE_TYPE_WEIGHT, 660),
                singleFontEnabled = pref(Preferences.KEY_ICON_FONT_MOBILE_TYPE_SINGLE, false),
                singleWeight = pref(Preferences.KEY_ICON_FONT_MOBILE_TYPE_SINGLE_WEIGHT, 400),
                onChange = { key, value -> changed(key, value) }
                        )
                    }
                    2 -> WifiSection(
                        activity = pref(Preferences.KEY_ICON_HIDE_WIFI_ACTIVITY, false),
                        type = pref(Preferences.KEY_ICON_HIDE_WIFI_TYPE, false),
                        hideConnected = pref(Preferences.KEY_ICON_HIDE_WIFI_UNAVAILABLE, false),
                        activityRight = pref(Preferences.KEY_ICON_WIFI_ACTIVITY_RIGHT, false),
                        standardMode = pref(Preferences.KEY_ICON_WIFI_STANDARD_MODE, 0),
                        standardMap = pref(Preferences.KEY_ICON_WIFI_STANDARD_MAP, "4,5,6,7,8"),
                        paddingEnabled = pref(Preferences.KEY_ICON_WIFI_PADDING, false),
                        paddingStart = pref(Preferences.KEY_ICON_WIFI_PADDING_START_VAL, 0f),
                        paddingEnd = pref(Preferences.KEY_ICON_WIFI_PADDING_END_VAL, 0f),
                        onChange = { key, value -> changed(key, value) }
                    )
                    3 -> {
                        NotificationSection(
                enabled = pref(Preferences.KEY_STATUSBAR_NOTIFICATION_MAX, false),
                maximum = pref(Preferences.KEY_STATUSBAR_NOTIFICATION_ICON_MAX, 3),
                onChange = { key, value -> changed(key, value) }
                        )
                        CompoundSection(
                slotMode = pref(Preferences.slotKey("compound_icon"), 0),
                alarm = pref(Preferences.KEY_ICON_COMPOUND_ALARM, false),
                zen = pref(Preferences.KEY_ICON_COMPOUND_ZEN, false),
                location = pref(Preferences.KEY_ICON_COMPOUND_LOCATION, false),
                volume = pref(Preferences.KEY_ICON_COMPOUND_VOLUME, false),
                priority = pref(Preferences.KEY_ICON_COMPOUND_PRIORITY, "location,alarm_clock,zen,volume"),
                slotModes = slotModes,
                onChange = { key, value -> changed(key, value) }
                        )
                        CarrierSection(
                hideOne = pref(Preferences.KEY_ICON_HIDE_CARRIER_ONE, false),
                hideTwo = pref(Preferences.KEY_ICON_HIDE_CARRIER_TWO, false),
                hideHd = pref(Preferences.KEY_ICON_HIDE_CARRIER_HD, false),
                hideLsOne = pref(Preferences.KEY_ICON_HIDE_LS_CARRIER_ONE, false),
                hideLsTwo = pref(Preferences.KEY_ICON_HIDE_LS_CARRIER_TWO, false),
                onChange = { key, value -> changed(key, value) }
                        )
                        SlotsSection(
                slotModes = slotModes,
                commonSlots = commonSlots,
                slotModeOf = { slot -> pref(Preferences.slotKey(slot), 0) },
                ignoreSysHide = pref(Preferences.KEY_ICON_IGNORE_SYS_HIDE, false),
                hidePrivacy = pref(Preferences.KEY_ICON_HIDE_PRIVACY, false),
                regionSampling = pref(Preferences.KEY_STATUSBAR_REGION_SAMPLING, 0),
                onChange = { key, value -> changed(key, value) }
                        )
                    }
                }

                if (systemUiRestartPending) {
                SmallTitle(stringResource(R.string.icon_apply_title))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.icon_restart_systemui),
                        summary = stringResource(R.string.icon_restart_systemui_summary),
                        onClick = {
                            // The remote (LSPosed daemon) copy is written asynchronously; make
                            // sure SystemUI starts after the daemon has the latest values.
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

                Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
            }
        }
    }
}

@Composable
private fun StackedSignalSection(
    enabled: Boolean,
    scale: Float,
    singleStyle: Int,
    stackedStyle: Int,
    signalAlphaFg: Float,
    signalAlphaBg: Float,
    signalAlphaError: Float,
    signalPaddingStart: Float,
    signalPaddingEnd: Float,
    hideWhenDisconnected: Boolean,
    hideWhenWifi: Boolean,
    showSingleBadge: Boolean,
    showStackedBadge: Boolean,
    showRoaming: Boolean,
    typeSize: Float,
    badgeSize: Float,
    width: Int,
    weight: Int,
    singleWeight: Int,
    paddingStart: Float,
    paddingEnd: Float,
    verticalOffset: Float,
    fontMode: Int,
    singleImportStatus: String?,
    stackedImportStatus: String?,
    singlePreview: ImageBitmap?,
    stackedPreview: ImageBitmap?,
    onImportSingle: () -> Unit,
    onImportStacked: () -> Unit,
    onChange: (String, Any) -> Unit
) {
    val signalStyles = listOf(
        stringResource(R.string.icon_stacked_signal_hyperos3),
        stringResource(R.string.icon_stacked_signal_ios26),
        stringResource(R.string.icon_stacked_signal_imported),
        stringResource(R.string.icon_stacked_signal_ios27)
    )
    SmallTitle(stringResource(R.string.icon_stacked_signal_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                enabled,
                stringResource(R.string.icon_custom_signal_icon),
                stringResource(R.string.icon_custom_signal_icon_summary)
            ) { onChange(Preferences.KEY_ICON_STACKED_ENABLED, it) }
            if (enabled) {
                SliderRow(stringResource(R.string.icon_icon_scale), scale, 0.5f, 1.5f) {
                    onChange(Preferences.KEY_ICON_STACKED_SCALE, it)
                }
                OverlayDropdownPreference(
                    title = stringResource(R.string.icon_stacked_signal_single_style),
                    items = signalStyles,
                    selectedIndex = singleStyle.coerceIn(0, signalStyles.lastIndex),
                    onSelectedIndexChange = {
                        onChange(Preferences.KEY_ICON_STACKED_SVG_SINGLE, it)
                    }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.icon_stacked_signal_stacked_style),
                    items = signalStyles,
                    selectedIndex = stackedStyle.coerceIn(0, signalStyles.lastIndex),
                    onSelectedIndexChange = {
                        onChange(Preferences.KEY_ICON_STACKED_SVG_STACKED, it)
                    }
                )
                SliderRow(stringResource(R.string.icon_stacked_signal_alpha_fg), signalAlphaFg, 0f, 1f) {
                    onChange(Preferences.KEY_ICON_STACKED_ALPHA_FG, it)
                }
                SliderRow(stringResource(R.string.icon_stacked_signal_alpha_bg), signalAlphaBg, 0f, 1f) {
                    onChange(Preferences.KEY_ICON_STACKED_ALPHA_BG, it)
                }
                SliderRow(stringResource(R.string.icon_stacked_signal_alpha_error), signalAlphaError, 0f, 1f) {
                    onChange(Preferences.KEY_ICON_STACKED_ALPHA_ERROR, it)
                }
                SliderRow(
                    stringResource(R.string.icon_stacked_signal_padding_start),
                    signalPaddingStart,
                    0f,
                    24f
                ) { onChange(Preferences.KEY_ICON_STACKED_PADDING_START, it) }
                SliderRow(
                    stringResource(R.string.icon_stacked_signal_padding_end),
                    signalPaddingEnd,
                    0f,
                    24f
                ) { onChange(Preferences.KEY_ICON_STACKED_PADDING_END, it) }
                Button(
                    onClick = onImportSingle,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) { Text(stringResource(R.string.icon_stacked_signal_import_single)) }
                if (singleImportStatus != null) {
                    Text(singleImportStatus, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (singlePreview != null && singleStyle == 2) {
                    Text(
                        stringResource(R.string.icon_stacked_signal_preview_single),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    Image(
                        bitmap = singlePreview,
                        contentDescription = stringResource(R.string.icon_stacked_signal_preview_single),
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp)
                    )
                }
                Button(
                    onClick = onImportStacked,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) { Text(stringResource(R.string.icon_stacked_signal_import_stacked)) }
                if (stackedImportStatus != null) {
                    Text(stackedImportStatus, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (stackedPreview != null && stackedStyle == 2) {
                    Text(
                        stringResource(R.string.icon_stacked_signal_preview_stacked),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    Image(
                        bitmap = stackedPreview,
                        contentDescription = stringResource(R.string.icon_stacked_signal_preview_stacked),
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp)
                    )
                }
                TextButton(
                    text = stringResource(R.string.icon_stacked_signal_restore),
                    onClick = {
                        onChange(Preferences.KEY_ICON_STACKED_SVG_SINGLE, 0)
                        onChange(Preferences.KEY_ICON_STACKED_SVG_STACKED, 0)
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
                TunerSwitch(
                    hideWhenDisconnected,
                    stringResource(R.string.icon_stacked_type_hide_disconnect),
                    stringResource(R.string.icon_stacked_type_hide_disconnect_summary)
                ) { onChange(Preferences.KEY_ICON_STACKED_TYPE_HIDE_DISCONNECT, it) }
                TunerSwitch(
                    hideWhenWifi,
                    stringResource(R.string.icon_stacked_type_hide_wifi),
                    stringResource(R.string.icon_stacked_type_hide_wifi_summary)
                ) { onChange(Preferences.KEY_ICON_STACKED_TYPE_HIDE_WIFI, it) }
                TunerSwitch(
                    showSingleBadge,
                    stringResource(R.string.icon_stacked_type_single_badge),
                    stringResource(R.string.icon_stacked_type_single_badge_summary)
                ) { onChange(Preferences.KEY_ICON_STACKED_TYPE_SHOW_SINGLE, it) }
                TunerSwitch(
                    showStackedBadge,
                    stringResource(R.string.icon_stacked_type_stacked_badge),
                    stringResource(R.string.icon_stacked_type_stacked_badge_summary)
                ) { onChange(Preferences.KEY_ICON_STACKED_TYPE_SHOW_STACKED, it) }
                TunerSwitch(
                    showRoaming,
                    stringResource(R.string.icon_stacked_type_roaming),
                    stringResource(R.string.icon_stacked_type_roaming_summary)
                ) { onChange(Preferences.KEY_ICON_STACKED_TYPE_ROAMING, it) }
                SliderRow(stringResource(R.string.icon_stacked_type_size), typeSize, 6f, 24f) {
                    onChange(Preferences.KEY_ICON_STACKED_TYPE_SIZE, it)
                }
                SliderRow(stringResource(R.string.icon_stacked_type_badge_size), badgeSize, 3f, 16f) {
                    onChange(Preferences.KEY_ICON_STACKED_TYPE_BADGE_SIZE, it)
                }
                IntSliderRow(stringResource(R.string.icon_stacked_type_width), width, 40, 100) {
                    onChange(Preferences.KEY_ICON_STACKED_TYPE_WIDTH_CONDENSED, it)
                }
                IntSliderRow(stringResource(R.string.icon_stacked_type_weight), weight, 100, 900) {
                    onChange(Preferences.KEY_ICON_STACKED_TYPE_WEIGHT, it)
                }
                IntSliderRow(stringResource(R.string.icon_stacked_type_single_weight), singleWeight, 100, 900) {
                    onChange(Preferences.KEY_ICON_STACKED_TYPE_SINGLE_WEIGHT, it)
                }
                val fontModes = listOf(
                    stringResource(R.string.icon_stacked_type_font_default),
                    stringResource(R.string.icon_stacked_type_font_condensed)
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.icon_stacked_type_font),
                    items = fontModes,
                    selectedIndex = if (fontMode == 2) 1 else 0,
                    onSelectedIndexChange = {
                        onChange(Preferences.KEY_ICON_STACKED_TYPE_FONT, if (it == 1) 2 else 0)
                    }
                )
                SliderRow(stringResource(R.string.icon_stacked_type_padding_start), paddingStart, 0f, 12f) {
                    onChange(Preferences.KEY_ICON_STACKED_TYPE_PADDING_START, it)
                }
                SliderRow(stringResource(R.string.icon_stacked_type_padding_end), paddingEnd, 0f, 12f) {
                    onChange(Preferences.KEY_ICON_STACKED_TYPE_PADDING_END, it)
                }
                SliderRow(stringResource(R.string.icon_stacked_type_offset), verticalOffset, -8f, 8f) {
                    onChange(Preferences.KEY_ICON_STACKED_TYPE_VERTICAL_OFFSET, it)
                }
            }
        }
    }
}

@Composable
private fun LeftContainerSection(
    mode: Int,
    zen: Boolean,
    volume: Boolean,
    hotspot: Boolean,
    alarmClock: Boolean,
    location: Boolean,
    bluetooth: Boolean,
    nfc: Boolean,
    vpn: Boolean,
    airplane: Boolean,
    headset: Boolean,
    compound: Boolean,
    onChange: (String, Any) -> Unit
) {
    val modes = listOf(
        stringResource(R.string.icon_left_mode_disabled),
        stringResource(R.string.icon_left_mode_home),
        stringResource(R.string.icon_left_mode_both)
    )
    SmallTitle(stringResource(R.string.icon_left_container_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_left_container_mode),
                summary = stringResource(R.string.icon_left_container_mode_summary),
                items = modes,
                selectedIndex = mode.coerceIn(0, modes.lastIndex),
                onSelectedIndexChange = { onChange(IconTunerOptions.KEY_LEFT_MODE, it) }
            )
            TunerSwitch(
                mode != IconTunerOptions.LEFT_MODE_DISABLED,
                stringResource(R.string.icon_left_container_master),
                stringResource(R.string.icon_left_container_master_summary)
            ) {
                onChange(
                    IconTunerOptions.KEY_LEFT_MODE,
                    if (it) IconTunerOptions.LEFT_MODE_HOME else IconTunerOptions.LEFT_MODE_DISABLED
                )
            }
            if (mode != IconTunerOptions.LEFT_MODE_DISABLED) {
                TunerSwitch(zen, stringResource(R.string.icon_left_zen)) {
                    onChange(Preferences.KEY_ICON_LEFT_ZEN, it)
                }
                TunerSwitch(volume, stringResource(R.string.icon_left_volume)) {
                    onChange(Preferences.KEY_ICON_LEFT_VOLUME, it)
                }
                TunerSwitch(hotspot, stringResource(R.string.icon_left_hotspot)) {
                    onChange(Preferences.KEY_ICON_LEFT_HOTSPOT, it)
                }
                TunerSwitch(alarmClock, stringResource(R.string.icon_left_alarm_clock)) {
                    onChange(Preferences.KEY_ICON_LEFT_ALARM_CLOCK, it)
                }
                TunerSwitch(location, stringResource(R.string.icon_left_location)) {
                    onChange(Preferences.KEY_ICON_LEFT_LOCATION, it)
                }
                TunerSwitch(bluetooth, stringResource(R.string.icon_left_bluetooth)) {
                    onChange(Preferences.KEY_ICON_LEFT_BLUETOOTH, it)
                }
                TunerSwitch(nfc, stringResource(R.string.icon_left_nfc)) {
                    onChange(Preferences.KEY_ICON_LEFT_NFC, it)
                }
                TunerSwitch(vpn, stringResource(R.string.icon_left_vpn)) {
                    onChange(Preferences.KEY_ICON_LEFT_VPN, it)
                }
                TunerSwitch(airplane, stringResource(R.string.icon_left_airplane)) {
                    onChange(Preferences.KEY_ICON_LEFT_AIRPLANE, it)
                }
                TunerSwitch(headset, stringResource(R.string.icon_left_headset)) {
                    onChange(Preferences.KEY_ICON_LEFT_HEADSET, it)
                }
                TunerSwitch(compound, stringResource(R.string.icon_compound_title)) {
                    onChange(Preferences.KEY_ICON_LEFT_COMPOUND, it)
                }
            }
        }
    }
}

@Composable
private fun CellularSection(
    activity: Boolean,
    type: Boolean,
    roam: Boolean,
    smallRoam: Boolean,
    roamGlobal: Boolean,
    voWifi: Boolean,
    volte: Boolean,
    volteNoService: Boolean,
    speechHd: Boolean,
    hideNonDefaultSim: Boolean,
    hideSimOne: Boolean,
    hideSimTwo: Boolean,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_cellular_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                activity,
                stringResource(R.string.icon_hide_data_activity),
                stringResource(R.string.icon_hide_data_activity_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_ACTIVITY, it) }
            TunerSwitch(
                type,
                stringResource(R.string.icon_hide_network_type),
                stringResource(R.string.icon_hide_network_type_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_TYPE, it) }
            TunerSwitch(
                roam,
                stringResource(R.string.icon_hide_roam_indicator),
                stringResource(R.string.icon_hide_roam_indicator_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_ROAM, it) }
            TunerSwitch(
                smallRoam,
                stringResource(R.string.icon_hide_small_roam_indicator),
                stringResource(R.string.icon_hide_small_roam_indicator_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_SMALL_ROAM, it) }
            TunerSwitch(
                roamGlobal,
                stringResource(R.string.icon_hide_roam_globally),
                stringResource(R.string.icon_hide_roam_globally_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_ROAM_GLOBAL, it) }
            TunerSwitch(
                voWifi,
                stringResource(R.string.icon_hide_vowifi),
                stringResource(R.string.icon_hide_vowifi_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_VOWIFI, it) }
            TunerSwitch(
                volte,
                stringResource(R.string.icon_hide_volte),
                stringResource(R.string.icon_hide_volte_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_VOLTE, it) }
            TunerSwitch(
                volteNoService,
                stringResource(R.string.icon_hide_volte_no_service),
                stringResource(R.string.icon_hide_volte_no_service_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_VOLTE_NO_SERVICE, it) }
            TunerSwitch(
                speechHd,
                stringResource(R.string.icon_hide_hd_call),
                stringResource(R.string.icon_hide_hd_call_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CELLULAR_SPEECH_HD, it) }
            TunerSwitch(
                hideNonDefaultSim,
                stringResource(R.string.icon_hide_non_default_sim_icon),
                stringResource(R.string.icon_hide_non_default_sim_icon_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_NON_DEFAULT_SIM, it) }
            TunerSwitch(
                hideSimOne,
                stringResource(R.string.icon_hide_sim_one),
                stringResource(R.string.icon_hide_sim_one_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_SIM_ONE, it) }
            TunerSwitch(
                hideSimTwo,
                stringResource(R.string.icon_hide_sim_two),
                stringResource(R.string.icon_hide_sim_two_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_SIM_TWO, it) }
        }
    }
}

@Composable
private fun WifiSection(
    activity: Boolean,
    type: Boolean,
    hideConnected: Boolean,
    activityRight: Boolean,
    standardMode: Int,
    standardMap: String,
    paddingEnabled: Boolean,
    paddingStart: Float,
    paddingEnd: Float,
    onChange: (String, Any) -> Unit
) {
    val standardModes = listOf(
        stringResource(R.string.icon_wifi_standard_follow),
        stringResource(R.string.icon_wifi_standard_hide),
        stringResource(R.string.icon_wifi_standard_native),
        stringResource(R.string.icon_wifi_standard_custom)
    )
    SmallTitle(stringResource(R.string.icon_wifi_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                activity,
                stringResource(R.string.icon_hide_wifi_activity),
                stringResource(R.string.icon_hide_wifi_activity_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_WIFI_ACTIVITY, it) }
            TunerSwitch(
                type,
                stringResource(R.string.icon_hide_wifi_standard),
                stringResource(R.string.icon_hide_wifi_standard_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_WIFI_TYPE, it) }
            TunerSwitch(
                hideConnected,
                stringResource(R.string.icon_hide_connected_wifi_icon),
                stringResource(R.string.icon_hide_connected_wifi_icon_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_WIFI_UNAVAILABLE, it) }
            TunerSwitch(
                activityRight,
                stringResource(R.string.icon_wifi_activity_right),
                stringResource(R.string.icon_wifi_activity_right_summary)
            ) { onChange(Preferences.KEY_ICON_WIFI_ACTIVITY_RIGHT, it) }
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_wifi_standard_mode),
                summary = stringResource(R.string.icon_wifi_standard_mode_summary),
                items = standardModes,
                selectedIndex = (standardMode - 0).coerceIn(0, standardModes.lastIndex),
                onSelectedIndexChange = { onChange(Preferences.KEY_ICON_WIFI_STANDARD_MODE, it) }
            )
            if (standardMode == 3) {
                TextField(
                    value = standardMap,
                    onValueChange = { onChange(Preferences.KEY_ICON_WIFI_STANDARD_MAP, it) },
                    label = stringResource(R.string.icon_wifi_standard_map),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            TunerSwitch(
                paddingEnabled,
                stringResource(R.string.icon_wifi_padding),
                stringResource(R.string.icon_wifi_padding_summary)
            ) { onChange(Preferences.KEY_ICON_WIFI_PADDING, it) }
            if (paddingEnabled) {
                SliderRow(stringResource(R.string.icon_wifi_padding_start), paddingStart, 0f, 24f) {
                    onChange(Preferences.KEY_ICON_WIFI_PADDING_START_VAL, it)
                }
                SliderRow(stringResource(R.string.icon_wifi_padding_end), paddingEnd, 0f, 24f) {
                    onChange(Preferences.KEY_ICON_WIFI_PADDING_END_VAL, it)
                }
            }
        }
    }
}

@Composable
private fun PositionSection(
    position: Int,
    customOrder: Set<String>,
    reorderHidden: Boolean,
    extraHidden: String,
    onChange: (String, Any) -> Unit
) {
    val positions = listOf(
        stringResource(R.string.icon_position_system),
        stringResource(R.string.icon_position_wifi_before_mobile),
        stringResource(R.string.icon_position_custom)
    )
    SmallTitle(stringResource(R.string.icon_position_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_position_title),
                summary = stringResource(R.string.icon_position_summary),
                items = positions,
                selectedIndex = position.coerceIn(0, positions.lastIndex),
                onSelectedIndexChange = { onChange(IconTunerOptions.KEY_POSITION, it) }
            )
            if (position == IconSlotPolicyConfig.POSITION_CUSTOM) {
                TextField(
                    value = customOrderDisplay(customOrder),
                    onValueChange = { value ->
                        onChange(IconTunerOptions.KEY_POSITION_VALUES, customOrderEntries(value))
                    },
                    label = stringResource(R.string.icon_position_custom_order),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            TunerSwitch(
                reorderHidden,
                stringResource(R.string.icon_position_reorder_hidden),
                stringResource(R.string.icon_position_reorder_hidden_summary)
            ) { onChange(IconTunerOptions.KEY_POSITION_REORDER, it) }
            TextField(
                value = extraHidden,
                onValueChange = { onChange(Preferences.KEY_ICON_EXT_BLOCKED, it) },
                label = stringResource(R.string.icon_position_extra_hidden),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

private fun customOrderDisplay(entries: Set<String>): String =
    IconSlotPolicy.parseLegacyOrder(entries).joinToString(",") { it.slot }

private fun customOrderEntries(value: String): Set<String> = value
    .split(',', '，', '\n')
    .asSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .mapIndexed { index, slot -> "$index:$slot" }
    .toCollection(LinkedHashSet())

@Composable
private fun NotificationSection(
    enabled: Boolean,
    maximum: Int,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_notification_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                enabled,
                stringResource(R.string.icon_notification_max),
                stringResource(R.string.icon_notification_max_summary)
            ) { onChange(Preferences.KEY_STATUSBAR_NOTIFICATION_MAX, it) }
            if (enabled) {
                IntSliderRow(stringResource(R.string.icon_notification_max_value), maximum, 1, 20) {
                    onChange(Preferences.KEY_STATUSBAR_NOTIFICATION_ICON_MAX, it)
                }
            }
        }
    }
}

@Composable
private fun CellularTypeSection(
    forceSingle: Boolean,
    useCustom: Boolean,
    customValue: String,
    swapSingle: Boolean,
    singleSizeEnabled: Boolean,
    singleSize: Float,
    drawableFontEnabled: Boolean,
    drawableWeight: Int,
    singleFontEnabled: Boolean,
    singleWeight: Int,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_cellular_type_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                forceSingle,
                stringResource(R.string.icon_cellular_type_single),
                stringResource(R.string.icon_cellular_type_single_summary)
            ) { onChange(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE, it) }
            TunerSwitch(
                useCustom,
                stringResource(R.string.icon_cellular_type_custom),
                stringResource(R.string.icon_cellular_type_custom_summary)
            ) { onChange(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM, it) }
            if (useCustom) {
                TextField(
                    value = customValue,
                    onValueChange = { onChange(Preferences.KEY_ICON_CELLULAR_TYPE_CUSTOM_VAL, it) },
                    label = stringResource(R.string.icon_cellular_type_custom_val),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            TunerSwitch(
                swapSingle,
                stringResource(R.string.icon_cellular_type_swap),
                stringResource(R.string.icon_cellular_type_swap_summary)
            ) { onChange(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SWAP, it) }
            TunerSwitch(
                singleSizeEnabled,
                stringResource(R.string.icon_cellular_type_size),
                stringResource(R.string.icon_cellular_type_size_summary)
            ) { onChange(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SIZE, it) }
            if (singleSizeEnabled) {
                SliderRow(stringResource(R.string.icon_cellular_type_size_value), singleSize, 6f, 24f) {
                    onChange(Preferences.KEY_ICON_CELLULAR_TYPE_SINGLE_SIZE_VAL, it)
                }
            }
            TunerSwitch(
                drawableFontEnabled,
                stringResource(R.string.icon_cellular_type_font),
                stringResource(R.string.icon_cellular_type_font_summary)
            ) { onChange(Preferences.KEY_ICON_FONT_MOBILE_TYPE, it) }
            if (drawableFontEnabled) {
                IntSliderRow(stringResource(R.string.icon_font_weight), drawableWeight, 100, 900) {
                    onChange(Preferences.KEY_ICON_FONT_MOBILE_TYPE_WEIGHT, it)
                }
            }
            TunerSwitch(
                singleFontEnabled,
                stringResource(R.string.icon_cellular_type_single_font),
                stringResource(R.string.icon_cellular_type_single_font_summary)
            ) { onChange(Preferences.KEY_ICON_FONT_MOBILE_TYPE_SINGLE, it) }
            if (singleFontEnabled) {
                IntSliderRow(stringResource(R.string.icon_font_weight), singleWeight, 100, 900) {
                    onChange(Preferences.KEY_ICON_FONT_MOBILE_TYPE_SINGLE_WEIGHT, it)
                }
            }
        }
    }
}

@Composable
private fun CompoundSection(
    slotMode: Int,
    alarm: Boolean,
    zen: Boolean,
    location: Boolean,
    volume: Boolean,
    priority: String,
    slotModes: List<String>,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_compound_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            OverlayDropdownPreference(
                title = stringResource(R.string.icon_compound_slot_mode),
                summary = stringResource(R.string.icon_compound_slot_mode_summary),
                items = slotModes,
                selectedIndex = slotMode.coerceIn(0, slotModes.lastIndex),
                onSelectedIndexChange = { onChange(Preferences.slotKey("compound_icon"), it) }
            )
            if (slotMode in 1..3) {
                TunerSwitch(
                    alarm,
                    stringResource(R.string.icon_compound_alarm),
                    stringResource(R.string.icon_compound_alarm_summary)
                ) { onChange(Preferences.KEY_ICON_COMPOUND_ALARM, it) }
                TunerSwitch(
                    zen,
                    stringResource(R.string.icon_compound_zen),
                    stringResource(R.string.icon_compound_zen_summary)
                ) { onChange(Preferences.KEY_ICON_COMPOUND_ZEN, it) }
                TunerSwitch(
                    location,
                    stringResource(R.string.icon_compound_location),
                    stringResource(R.string.icon_compound_location_summary)
                ) { onChange(Preferences.KEY_ICON_COMPOUND_LOCATION, it) }
                TunerSwitch(
                    volume,
                    stringResource(R.string.icon_compound_volume),
                    stringResource(R.string.icon_compound_volume_summary)
                ) { onChange(Preferences.KEY_ICON_COMPOUND_VOLUME, it) }
                TextField(
                    value = priority,
                    onValueChange = { onChange(Preferences.KEY_ICON_COMPOUND_PRIORITY, it) },
                    label = stringResource(R.string.icon_compound_priority),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CarrierSection(
    hideOne: Boolean,
    hideTwo: Boolean,
    hideHd: Boolean,
    hideLsOne: Boolean,
    hideLsTwo: Boolean,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_carrier_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            TunerSwitch(
                hideOne,
                stringResource(R.string.icon_hide_carrier_one),
                stringResource(R.string.icon_hide_carrier_one_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CARRIER_ONE, it) }
            TunerSwitch(
                hideTwo,
                stringResource(R.string.icon_hide_carrier_two),
                stringResource(R.string.icon_hide_carrier_two_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CARRIER_TWO, it) }
            TunerSwitch(
                hideHd,
                stringResource(R.string.icon_hide_carrier_hd),
                stringResource(R.string.icon_hide_carrier_hd_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_CARRIER_HD, it) }
            TunerSwitch(
                hideLsOne,
                stringResource(R.string.icon_hide_ls_carrier_one),
                stringResource(R.string.icon_hide_ls_carrier_one_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_LS_CARRIER_ONE, it) }
            TunerSwitch(
                hideLsTwo,
                stringResource(R.string.icon_hide_ls_carrier_two),
                stringResource(R.string.icon_hide_ls_carrier_two_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_LS_CARRIER_TWO, it) }
        }
    }
}

@Composable
private fun SlotsSection(
    slotModes: List<String>,
    commonSlots: List<String>,
    slotModeOf: (String) -> Int,
    ignoreSysHide: Boolean,
    hidePrivacy: Boolean,
    regionSampling: Int,
    onChange: (String, Any) -> Unit
) {
    SmallTitle(stringResource(R.string.icon_slots_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            commonSlots.forEach { slot ->
                val mode = slotModeOf(slot)
                OverlayDropdownPreference(
                    title = slot.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    items = slotModes,
                    selectedIndex = mode.coerceIn(0, slotModes.lastIndex),
                    onSelectedIndexChange = { index ->
                        onChange(Preferences.slotKey(slot), index)
                    }
                )
            }
            TunerSwitch(
                ignoreSysHide,
                stringResource(R.string.icon_ignore_system_hiding),
                stringResource(R.string.icon_ignore_system_hiding_summary)
            ) { onChange(Preferences.KEY_ICON_IGNORE_SYS_HIDE, it) }
            TunerSwitch(
                hidePrivacy,
                stringResource(R.string.icon_hide_privacy_indicator),
                stringResource(R.string.icon_hide_privacy_indicator_summary)
            ) { onChange(Preferences.KEY_ICON_HIDE_PRIVACY, it) }
            TunerSwitch(
                regionSampling != 0,
                stringResource(R.string.icon_region_sampling),
                stringResource(R.string.icon_region_sampling_summary)
            ) { onChange(Preferences.KEY_STATUSBAR_REGION_SAMPLING, if (it) 1 else 0) }
        }
    }
}

@Composable
private fun SliderRow(title: String, value: Float, rangeStart: Float, rangeEnd: Float, onValue: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(title)
        Slider(
            value = value.coerceIn(rangeStart, rangeEnd),
            onValueChange = onValue,
            valueRange = rangeStart..rangeEnd
        )
    }
}

@Composable
private fun IntSliderRow(title: String, value: Int, rangeStart: Int, rangeEnd: Int, onValue: (Int) -> Unit) {
    SliderRow(title, value.toFloat(), rangeStart.toFloat(), rangeEnd.toFloat()) {
        onValue(it.roundToInt().coerceIn(rangeStart, rangeEnd))
    }
}
