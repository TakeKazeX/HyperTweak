package com.takekazex.hypertweak.ui.page

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

@Composable
fun DockBackgroundPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scroll = MiuixScrollBehavior()
    var saved by rememberSaveable { mutableStateOf(Preferences.getString(DockConfig.KEY, DockConfig().encode())) }
    var draft by rememberSaveable { mutableStateOf(DockSettingsWriter.state.value.takeIf { it.saving }?.requested ?: saved) }
    val config = DockConfig.decode(draft) ?: DockConfig()
    val writeState by DockSettingsWriter.state.collectAsState()
    var seenAcknowledgement by remember { mutableStateOf(writeState.acknowledged) }
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
            SmallTitle(stringResource(R.string.dock_geometry))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    DockSlider(stringResource(R.string.dock_height), config.height, 40f..180f,
                        { change(config.copy(height = it)) })
                    DockSlider(stringResource(R.string.dock_horizontal), config.horizontalMargin, 0f..100f,
                        { change(config.copy(horizontalMargin = it)) })
                    DockSlider(stringResource(R.string.dock_bottom), config.bottomMargin, 0f..160f,
                        { change(config.copy(bottomMargin = it)) })
                    DockSlider(stringResource(R.string.dock_radius), config.radius, 0f..90f,
                        { change(config.copy(radius = it)) })
                    TextButton(text = stringResource(R.string.dock_reset), onClick = { change(DockConfig(enabled = config.enabled, style = config.style)) })
                }
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
private fun DockSlider(title: String, value: Int, range: ClosedFloatingPointRange<Float>, onChange: (Int) -> Unit) {
    Text("$title · $value dp")
    Slider(value = value.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = range)
    Spacer(Modifier.height(12.dp))
}
