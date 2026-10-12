package com.takekazex.hypertweak.ui.page

import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.dock.DockConfig
import com.takekazex.hypertweak.dock.DockSettingsWriter
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.ui.effect.DockPreviewView
import com.takekazex.hypertweak.ui.effect.decodeBundledGlassPreviewSample
import com.takekazex.hypertweak.ui.effect.decodeGlassPreviewImage
import com.takekazex.hypertweak.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
fun DockBackgroundPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scroll = MiuixScrollBehavior()
    var saved by rememberSaveable { mutableStateOf(Preferences.getString(DockConfig.KEY, DockConfig().encode())) }
    var draft by rememberSaveable { mutableStateOf(DockSettingsWriter.state.value.takeIf { it.saving }?.requested ?: saved) }
    val config = DockConfig.decode(draft) ?: DockConfig()
    val writeState by DockSettingsWriter.state.collectAsState()
    var seenAcknowledgement by remember { mutableStateOf(writeState.acknowledged) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var nativeReady by remember { mutableStateOf(true) }
    var image by remember { mutableStateOf(decodeBundledGlassPreviewSample(context).bitmap) }
    var imageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(Unit) {
        saved = Preferences.getString(DockConfig.KEY, DockConfig().encode())
        draft = DockSettingsWriter.state.value.takeIf { it.saving }?.requested ?: saved
    }
    LaunchedEffect(writeState) {
        writeState.acknowledged?.takeIf { it != seenAcknowledgement }?.let { saved = it; seenAcknowledgement = it }
        if (writeState.failed && draft == writeState.requested) {
            draft = saved
            Toast.makeText(context, R.string.dock_save_failed, Toast.LENGTH_LONG).show()
        }
    }
    fun change(value: DockConfig) {
        draft = value.encode()
        DockSettingsWriter.submit(draft)
    }
    LaunchedEffect(imageUri) {
        imageUri?.let { encoded ->
            loading = true
            runCatching { withContext(Dispatchers.IO) { decodeGlassPreviewImage(context, encoded.toUri()).bitmap } }
                .onSuccess { image = it }
                .onFailure { DebugLog.w("DockPreview", "image read failed", it); Toast.makeText(context, R.string.glass_preview_image_load_failed, Toast.LENGTH_LONG).show() }
            loading = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            imageUri = uri.toString()
        }
    }
    Scaffold(topBar = { TopAppBar(title = stringResource(R.string.dock_title), scrollBehavior = scroll,
        navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.glass_back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .nestedScroll(scroll.nestedScrollConnection).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column {
                    SwitchPreference(title = stringResource(R.string.dock_title), summary = stringResource(R.string.dock_summary),
                        checked = config.enabled, onCheckedChange = { change(config.copy(enabled = it)) })
                    OverlayDropdownPreference(title = stringResource(R.string.dock_material), items = listOf(stringResource(R.string.dock_glass), stringResource(R.string.dock_frosted)),
                        selectedIndex = config.style, onSelectedIndexChange = { change(config.copy(style = it)) })

                }
            }
            SmallTitle(stringResource(R.string.glass_preview_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column {
                    if (loading) Text(stringResource(R.string.glass_preview_loading_image), Modifier.padding(16.dp))
                    AndroidView(factory = { DockPreviewView(it) }, update = { view ->
                        view.onNativeState = { nativeReady = it }
                        view.update(config, dark, image)
                    }, onRelease = { it.dispose() }, modifier = Modifier.fillMaxWidth().height(220.dp).padding(12.dp))
                    Text(stringResource(if (nativeReady) R.string.dock_preview_icons else R.string.glass_preview_native_unavailable), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    TextButton(text = stringResource(R.string.glass_preview_change_image), onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
            TabRowWithContour(
                tabs = listOf(stringResource(R.string.dock_layout_tab), stringResource(R.string.dock_glass_tab)),
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                colors = TabRowDefaults.tabRowColors(
                    backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                    selectedBackgroundColor = MiuixTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)
            )
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column {
                    if (selectedTab == 0) {
                        DockSlider(stringResource(R.string.dock_height), config.height, 40..180, 94, "dp",
                            { change(config.copy(height = it)) })
                        DockSlider(stringResource(R.string.dock_horizontal), config.horizontalMargin, 0..100, 10, "dp",
                            { change(config.copy(horizontalMargin = it)) })
                        DockSlider(stringResource(R.string.dock_bottom), config.bottomMargin, 0..160, 36, "dp",
                            { change(config.copy(bottomMargin = it)) })
                        DockSlider(stringResource(R.string.dock_radius), config.radius, 0..90, 28, "dp",
                            { change(config.copy(radius = it)) })
                        TextButton(text = stringResource(R.string.dock_reset), modifier = Modifier.padding(12.dp), onClick = {
                            change(config.copy(height = 94, horizontalMargin = 10, bottomMargin = 36, radius = 28))
                        })
                    } else {
                        DockSlider(stringResource(R.string.dock_blur), config.blur, DockConfig.BLUR_RANGE, DockConfig.DEFAULT_BLUR, "px",
                            { change(config.copy(blur = it)) })
                        if (config.style == DockConfig.GLASS) {
                            DockSlider(stringResource(R.string.dock_opacity), config.opacity, DockConfig.PERCENT_RANGE, 100, "%",
                                { change(config.copy(opacity = it)) }, summary = stringResource(R.string.dock_opacity_summary))
                        } else {
                            DockSlider(stringResource(R.string.dock_tint), config.tint, DockConfig.PERCENT_RANGE, 100, "%",
                                { change(config.copy(tint = it)) })
                        }
                        TextButton(text = stringResource(R.string.dock_reset_glass), modifier = Modifier.padding(12.dp), onClick = {
                            change(if (config.style == DockConfig.GLASS) config.copy(blur = DockConfig.DEFAULT_BLUR, opacity = 100)
                                else config.copy(blur = DockConfig.DEFAULT_BLUR, tint = 100))
                        })
                    }
                }
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
private fun DockSlider(title: String, value: Int, range: IntRange, defaultValue: Int, unit: String, onChange: (Int) -> Unit, summary: String = "") {
    var showEditor by remember(title) { mutableStateOf(false) }
    ArrowPreference(
        title = title, summary = summary,
        endActions = { Text("$value $unit", color = MiuixTheme.colorScheme.onSurfaceVariantActions) },
        onClick = { showEditor = true },
        bottomAction = {
            Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                showKeyPoints = true, keyPoints = listOf(defaultValue.toFloat()))
        }
    )
    IntValueDialog(show = showEditor, title = title, summary = summary, suffix = unit, range = range,
        currentValue = { value }, emptyValue = value, onValueConfirmed = onChange,
        onDismissRequest = { showEditor = false })
}
