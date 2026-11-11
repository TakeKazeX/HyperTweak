package com.takekazex.hypertweak.ui.page.glass

import com.takekazex.hypertweak.ui.components.slider.rememberSliderEdit
import com.takekazex.hypertweak.ui.components.slider.SliderEditState

import com.takekazex.hypertweak.ui.components.dialog.IntValueDialog
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
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.settings.contract.NativeDockGlassGeometry
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.effect.DockPreviewView
import com.takekazex.hypertweak.ui.effect.decodeBundledGlassPreviewSample
import com.takekazex.hypertweak.ui.effect.decodeGlassPreviewImage
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
fun DockBackgroundPage(onBack: () -> Unit) {
    val settingsStore = LocalAppSettingsStore.current
    val context = LocalContext.current
    val scroll = MiuixScrollBehavior()
    fun storedEnabled() = settingsStore.getBoolean(PlatformSettingsKeys.KEY_NATIVE_PAD_DOCK, false)
    fun storedGeometry() = NativeDockGlassGeometry.unpack(
        settingsStore.getInt(PlatformSettingsKeys.KEY_NATIVE_DOCK_GLASS, NativeDockGlassGeometry.DEFAULT_PACKED)) ?: NativeDockGlassGeometry.DEFAULT
    var enabled by rememberSaveable { mutableStateOf(storedEnabled()) }
    var packed by rememberSaveable { mutableIntStateOf(storedGeometry().pack()) }
    val geometry = NativeDockGlassGeometry.unpack(packed) ?: NativeDockGlassGeometry.DEFAULT
    // Share transient drag values with the preview; only change() writes the setting.
    val heightSlider = rememberSliderEdit(geometry.height.toFloat())
    val marginSlider = rememberSliderEdit(geometry.margin.toFloat())
    val bottomSlider = rememberSliderEdit(geometry.bottom.toFloat())
    val radiusSlider = rememberSliderEdit(geometry.radius.toFloat())
    val previewGeometry = NativeDockGlassGeometry(heightSlider.value.roundToInt(), marginSlider.value.roundToInt(),
        bottomSlider.value.roundToInt(), radiusSlider.value.roundToInt(), geometry.adaptive)
    var nativeReady by remember { mutableStateOf(true) }
    var image by remember { mutableStateOf(decodeBundledGlassPreviewSample(context).bitmap) }
    var imageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()
    LaunchedEffect(Unit) {
        // The retired system_server surface style hands an enabled Dock over to the native glass.
        withContext(Dispatchers.IO) { settingsStore.launcher.migrateLegacyDockBackground() }
        enabled = storedEnabled()
        packed = storedGeometry().pack()
    }
    fun change(next: NativeDockGlassGeometry) {
        packed = next.pack()
        settingsStore.putInt(PlatformSettingsKeys.KEY_NATIVE_DOCK_GLASS, packed)
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
    val default = NativeDockGlassGeometry.DEFAULT
    Scaffold(topBar = { TopAppBar(title = stringResource(R.string.dock_title), scrollBehavior = scroll,
        navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.glass_back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .nestedScroll(scroll.nestedScrollConnection).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.dock_title),
                    summary = stringResource(R.string.dock_summary),
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        settingsStore.putBoolean(PlatformSettingsKeys.KEY_NATIVE_PAD_DOCK, it)
                    },
                )
            }
            SmallTitle(stringResource(R.string.glass_preview_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column {
                    if (loading) Text(stringResource(R.string.glass_preview_loading_image), Modifier.padding(16.dp))
                    AndroidView(factory = { DockPreviewView(it) }, update = { view ->
                        view.onNativeState = { nativeReady = it }
                        view.update(previewGeometry, dark, image)
                    }, onRelease = { it.dispose() }, modifier = Modifier.fillMaxWidth().height(220.dp).padding(12.dp))
                    Text(stringResource(if (nativeReady) R.string.dock_preview_icons else R.string.glass_preview_native_unavailable), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    TextButton(text = stringResource(R.string.glass_preview_change_image), onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
            SmallTitle(stringResource(R.string.dock_native_geometry))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column {
                    SwitchPreference(
                        title = stringResource(R.string.dock_adaptive_width),
                        summary = stringResource(R.string.dock_adaptive_width_summary),
                        checked = geometry.adaptive,
                        onCheckedChange = { change(geometry.copy(adaptive = it)) },
                    )
                    DockSlider(stringResource(R.string.dock_height), geometry.height, NativeDockGlassGeometry.HEIGHT, default.height, "dp",
                        { change(geometry.copy(height = it)) }, summary = stringResource(R.string.dock_native_height_summary), editState = heightSlider)
                    // Adaptive width owns the side margin; the slider shows the locked value.
                    DockSlider(stringResource(R.string.dock_horizontal), geometry.effectiveMargin, NativeDockGlassGeometry.MARGIN, default.margin, "dp",
                        { change(geometry.copy(margin = it)) }, editState = marginSlider, enabled = !geometry.adaptive)
                    DockSlider(stringResource(R.string.dock_bottom), geometry.bottom, NativeDockGlassGeometry.BOTTOM, default.bottom, "dp",
                        { change(geometry.copy(bottom = it)) }, editState = bottomSlider)
                    DockSlider(stringResource(R.string.dock_radius), geometry.radius, NativeDockGlassGeometry.RADIUS, default.radius, "dp",
                        { change(geometry.copy(radius = it)) }, editState = radiusSlider)
                    TextButton(text = stringResource(R.string.dock_reset), modifier = Modifier.padding(12.dp), onClick = { change(default.copy(adaptive = geometry.adaptive)) })
                }
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
internal fun DockSlider(title: String, value: Int, range: IntRange, defaultValue: Int, unit: String, onChange: (Int) -> Unit, summary: String = "", editState: SliderEditState? = null, enabled: Boolean = true) {
    val slider = editState ?: rememberSliderEdit(value.toFloat())
    var showEditor by remember(title) { mutableStateOf(false) }
    val shown = if (enabled) slider.value else value.toFloat()
    ArrowPreference(
        title = title, summary = summary, enabled = enabled,
        endActions = { Text("${shown.roundToInt()} $unit", color = MiuixTheme.colorScheme.onSurfaceVariantActions) },
        onClick = { showEditor = true },
        bottomAction = {
            Slider(value = shown, onValueChange = slider::update, enabled = enabled,
                onValueChangeFinished = { slider.finish { it.roundToInt().toFloat() }?.let { onChange(it.toInt()) } },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                showKeyPoints = true, keyPoints = listOf(defaultValue.toFloat()))
        }
    )
    IntValueDialog(show = showEditor && enabled, title = title, summary = summary, suffix = unit, range = range,
        currentValue = { value }, emptyValue = value, onValueConfirmed = onChange,
        onDismissRequest = { showEditor = false })
}
