package com.takekazex.hypertweak.ui.page.glass

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.SystemUiSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.chrome.AppTextButton as TextButton
import com.takekazex.hypertweak.ui.components.MiuixExpandableContent
import com.takekazex.hypertweak.ui.components.dialog.AppDialog
import com.takekazex.hypertweak.ui.effect.GlassPreviewConfig
import com.takekazex.hypertweak.ui.effect.GlassTunerPreset
import com.takekazex.hypertweak.ui.effect.GlassTunerPresetStore
import com.takekazex.hypertweak.ui.effect.GlassTunerValues
import com.takekazex.hypertweak.ui.effect.NativeGlassPreviewView
import com.takekazex.hypertweak.ui.effect.decodeBundledGlassPreviewSample
import com.takekazex.hypertweak.ui.effect.decodeGlassPreviewImage
import com.takekazex.hypertweak.util.logging.DebugLog
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * OS4 material-style glass tuner. Overrides the blur/blend resources behind 清透磨砂 and
 * 柔光玻璃 (Settings → Display → Visual style) with user-tuned values, read by
 * `GlassMaterialHooker` in SystemUI. State is kept locally rather than hoisted into
 * `MainActivity`, so every change reports SystemUI to the shared Home restart dialog; the page
 * also keeps its local restart affordance.
 */
@Composable
fun GlassTunerPage(onBack: () -> Unit) {
    val settingsStore = LocalAppSettingsStore.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()

    // Local remember-backed state so toggles update instantly; the remote preference write is
    // async, so reading Preferences directly in composition made switches bounce back.
    val prefs = androidx.compose.runtime.remember { mutableStateMapOf<String, Any>() }

    @Suppress("UNCHECKED_CAST")
    fun <T> pref(key: String, default: T): T {
        val existing = prefs[key]
        if (existing != null) return existing as T
        val stored: T = when (default) {
            is Boolean -> settingsStore.getBoolean(key, default as Boolean) as T
            is Float -> settingsStore.getFloat(key, default as Float) as T
            else -> default
        }
        prefs[key] = stored as Any
        return stored
    }

    fun changed(key: String, value: Any) {
        DebugLog.i("GlassTuner", "preference changed: $key=$value")
        prefs[key] = value
        when (value) {
            is Boolean -> settingsStore.putBoolean(key, value)
            is Float -> settingsStore.putFloat(key, value)
        }
    }

    val enabled = pref(SystemUiSettingsKeys.KEY_GLASS_TUNER_ENABLED, false)
    val blendAlpha = pref(SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_ALPHA, 1f)
    val blendLightness = pref(SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_LIGHTNESS, 1f)
    val radiusScale = pref(SystemUiSettingsKeys.KEY_GLASS_TUNER_RADIUS_SCALE, 1f)
    val glassOpacity = pref(SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_OPACITY, 1f)
    val glassTone = pref(SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_TONE, 1f)

    val bundledPreview = remember(context) { decodeBundledGlassPreviewSample(context) }
    var previewBitmap by remember { mutableStateOf(bundledPreview.bitmap) }
    var previewDark by remember { mutableStateOf(bundledPreview.darkTheme) }
    var imageLoading by remember { mutableStateOf(false) }
    var materialStyle by remember { mutableIntStateOf(readMaterialStyle(context)) }
    var previewBlendAlpha by remember(blendAlpha) { mutableFloatStateOf(blendAlpha) }
    var previewBlendLightness by remember(blendLightness) { mutableFloatStateOf(blendLightness) }
    var previewRadiusScale by remember(radiusScale) { mutableFloatStateOf(radiusScale) }
    var previewGlassOpacity by remember(glassOpacity) { mutableFloatStateOf(glassOpacity) }
    var previewGlassTone by remember(glassTone) { mutableFloatStateOf(glassTone) }
    val presetPreferences = remember(context) {
        context.getSharedPreferences(GlassTunerPresetStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    var presets by remember(presetPreferences) {
        mutableStateOf(GlassTunerPresetStore.load(presetPreferences))
    }
    var presetToDelete by remember { mutableStateOf<GlassTunerPreset?>(null) }
    var presetToRename by remember { mutableStateOf<GlassTunerPreset?>(null) }
    val renameInput = remember { TextFieldState() }
    var pendingPresetImport by remember { mutableStateOf<GlassTunerValues?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            imageLoading = true
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { decodeGlassPreviewImage(context, uri) }
                }
                imageLoading = false
                result.onSuccess { image ->
                    previewBitmap = image.bitmap
                    previewDark = image.darkTheme
                }.onFailure {
                    DebugLog.w("GlassTuner", "preview image decode failed", it)
                    Toast.makeText(context, R.string.glass_preview_image_load_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun currentTunerValues() = GlassTunerValues(
        enabled = enabled,
        blendAlpha = previewBlendAlpha,
        blendLightness = previewBlendLightness,
        radiusScale = previewRadiusScale,
        glassOpacity = previewGlassOpacity,
        glassTone = previewGlassTone,
    )

    fun applyTunerValues(values: GlassTunerValues) {
        prefs[SystemUiSettingsKeys.KEY_GLASS_TUNER_ENABLED] = values.enabled
        prefs[SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_ALPHA] = values.blendAlpha
        prefs[SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_LIGHTNESS] = values.blendLightness
        prefs[SystemUiSettingsKeys.KEY_GLASS_TUNER_RADIUS_SCALE] = values.radiusScale
        prefs[SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_OPACITY] = values.glassOpacity
        prefs[SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_TONE] = values.glassTone
        settingsStore.putBoolean(SystemUiSettingsKeys.KEY_GLASS_TUNER_ENABLED, values.enabled)
        settingsStore.putFloat(SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_ALPHA, values.blendAlpha)
        settingsStore.putFloat(SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_LIGHTNESS, values.blendLightness)
        settingsStore.putFloat(SystemUiSettingsKeys.KEY_GLASS_TUNER_RADIUS_SCALE, values.radiusScale)
        settingsStore.putFloat(SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_OPACITY, values.glassOpacity)
        settingsStore.putFloat(SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_TONE, values.glassTone)
        previewBlendAlpha = values.blendAlpha
        previewBlendLightness = values.blendLightness
        previewRadiusScale = values.radiusScale
        previewGlassOpacity = values.glassOpacity
        previewGlassTone = values.glassTone
    }

    fun saveCurrentPreset(name: String, announce: Boolean = true): Boolean {
        DebugLog.i("GlassTuner", "preset save requested")
        val normalizedName = name.trim().take(40)
        if (normalizedName.isEmpty()) {
            DebugLog.w("GlassTuner", "preset save rejected: empty name")
            Toast.makeText(context, R.string.glass_preset_name_required, Toast.LENGTH_SHORT).show()
            return false
        }
        val updated = (presets.filterNot { it.name.equals(normalizedName, ignoreCase = true) } +
            GlassTunerPreset(normalizedName, currentTunerValues(), System.currentTimeMillis()))
            .takeLast(GlassTunerPresetStore.MAX_PRESETS)
        if (!GlassTunerPresetStore.save(presetPreferences, updated)) {
            DebugLog.e("GlassTuner", "preset save failed: SharedsettingsStore.commit returned false")
            Toast.makeText(context, R.string.glass_preset_save_failed, Toast.LENGTH_LONG).show()
            return false
        }
        presets = GlassTunerPresetStore.load(presetPreferences)
        DebugLog.i("GlassTuner", "preset save succeeded; count=${presets.size}")
        if (announce) {
            Toast.makeText(
                context,
                resources.getString(R.string.glass_preset_saved_named, normalizedName),
                Toast.LENGTH_SHORT,
            ).show()
        }
        return true
    }

    fun suggestPresetName(): String {
        var index = 1
        while (index <= GlassTunerPresetStore.MAX_PRESETS) {
            val candidate = resources.getString(R.string.glass_preset_default_name, index)
            if (presets.none { it.name.equals(candidate, ignoreCase = true) }) return candidate
            index++
        }
        return resources.getString(R.string.glass_preset_default_name, System.currentTimeMillis())
    }

    fun renamePreset(target: GlassTunerPreset, rawName: String): Boolean {
        val newName = rawName.trim().take(40)
        if (newName.isEmpty()) {
            Toast.makeText(context, R.string.glass_preset_name_required, Toast.LENGTH_SHORT).show()
            return false
        }
        if (presets.any { it.name != target.name && it.name.equals(newName, ignoreCase = true) }) {
            Toast.makeText(context, R.string.glass_preset_name_duplicate, Toast.LENGTH_SHORT).show()
            DebugLog.w("GlassTuner", "preset rename rejected: duplicate name")
            return false
        }
        val updated = presets.map {
            if (it.name == target.name) it.copy(name = newName, updatedAtMillis = System.currentTimeMillis())
            else it
        }
        if (!GlassTunerPresetStore.save(presetPreferences, updated)) {
            DebugLog.e("GlassTuner", "preset rename failed: SharedsettingsStore.commit returned false")
            Toast.makeText(context, R.string.glass_preset_rename_failed, Toast.LENGTH_LONG).show()
            return false
        }
        presets = GlassTunerPresetStore.load(presetPreferences)
        DebugLog.i("GlassTuner", "preset renamed; count=${presets.size}")
        Toast.makeText(
            context,
            resources.getString(R.string.glass_preset_renamed, newName),
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    fun importPresetFromClipboard() {
        DebugLog.i("GlassTuner", "clipboard preset import requested")
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val code = runCatching {
            clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
        if (code.isBlank()) {
            DebugLog.d("GlassTuner", "clipboard preset import rejected: empty clipboard")
            Toast.makeText(context, R.string.glass_preset_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { GlassTunerPresetStore.parseShareCode(code) }
            .onSuccess {
                pendingPresetImport = it
                DebugLog.i("GlassTuner", "clipboard preset code parsed; awaiting overwrite confirmation")
            }
            .onFailure { error ->
                if (error is IllegalArgumentException) {
                    DebugLog.d("GlassTuner", "clipboard preset rejected by validation")
                } else {
                    DebugLog.e("GlassTuner", "clipboard preset import failed", error)
                }
                Toast.makeText(context, R.string.glass_preset_invalid_code, Toast.LENGTH_LONG).show()
            }
    }

    fun confirmPresetImport(saveCurrentFirst: Boolean) {
        val imported = pendingPresetImport ?: return
        DebugLog.i("GlassTuner", "preset import confirmed; saveCurrentFirst=$saveCurrentFirst")
        if (saveCurrentFirst && !saveCurrentPreset(suggestPresetName(), announce = false)) return
        applyTunerValues(imported)
        pendingPresetImport = null
        Toast.makeText(
            context,
            if (saveCurrentFirst) R.string.glass_preset_saved_and_imported else R.string.glass_preset_imported,
            Toast.LENGTH_SHORT,
        ).show()
        DebugLog.i("GlassTuner", "clipboard preset import applied")
    }

    fun sharePreset(values: GlassTunerValues) {
        val code = GlassTunerPresetStore.createShareCode(values)
        runCatching {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.glass_preset_share), code))
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, code)
            }
            context.startActivity(Intent.createChooser(sendIntent, resources.getString(R.string.glass_preset_share)))
            DebugLog.i("GlassTuner", "preset share chooser opened")
        }.onFailure { error ->
            DebugLog.e("GlassTuner", "preset share failed", error)
            Toast.makeText(context, R.string.glass_preset_share_failed, Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                materialStyle = readMaterialStyle(context)
            }
        }
        val uri = Settings.Secure.getUriFor("material_style")
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    AppScrollPage(
        title = stringResource(R.string.glass_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.glass_back),
    ) {

        SmallTitle(stringResource(R.string.glass_preview_title))
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            if (previewDark) R.string.glass_preview_auto_dark
                            else R.string.glass_preview_auto_light
                        ),
                        modifier = Modifier.weight(1f),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    TextButton(
                        text = stringResource(R.string.glass_preview_change_image),
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                if (imageLoading) {
                    PreviewEmptyState(stringResource(R.string.glass_preview_loading_image))
                } else {
                    val bitmap = previewBitmap
                    val imagePanTarget = remember(bitmap) { mutableStateOf<NativeGlassPreviewView?>(null) }
                    AndroidView(
                        factory = {
                            NativeGlassPreviewView(it).also { view -> imagePanTarget.value = view }
                        },
                        update = { view ->
                            view.update(
                                GlassPreviewConfig(
                                    darkTheme = previewDark,
                                    materialStyle = materialStyle,
                                    tunerEnabled = enabled,
                                    blendAlpha = previewBlendAlpha,
                                    blendLightness = previewBlendLightness,
                                    radiusScale = previewRadiusScale,
                                    glassOpacity = previewGlassOpacity,
                                    glassTone = previewGlassTone,
                                ),
                                bitmap,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(216.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .pointerInput(bitmap) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial,
                                    )
                                    var lastPosition = down.position
                                    var totalX = 0f
                                    var totalY = 0f
                                    var draggingBackground = false
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes.firstOrNull() ?: break
                                        if (!change.pressed) break
                                        val delta = change.position - lastPosition
                                        lastPosition = change.position
                                        totalX += delta.x
                                        totalY += delta.y
                                        if (!draggingBackground &&
                                            abs(totalY) > viewConfiguration.touchSlop &&
                                            abs(totalY) > abs(totalX)
                                        ) {
                                            draggingBackground = true
                                        }
                                        if (draggingBackground) {
                                            change.consume()
                                            imagePanTarget.value?.panBackground(delta.y)
                                        }
                                    }
                                }
                            }
                    )
                }
            }
        }

        AppSection(title = stringResource(R.string.glass_parameters)) {
            SwitchPreference(
                checked = enabled,
                onCheckedChange = { changed(SystemUiSettingsKeys.KEY_GLASS_TUNER_ENABLED, it) },
                title = stringResource(R.string.glass_custom_parameters),
                summary = stringResource(R.string.glass_parameters_summary)
            )
            MiuixExpandableContent(visible = enabled) {
                Column(Modifier.fillMaxWidth()) {
                    GlassSliderPreference(
                        title = stringResource(R.string.glass_blend_opacity),
                        summary = stringResource(R.string.glass_blend_opacity_summary),
                        value = previewBlendAlpha,
                        rangeStart = 0f,
                        rangeEnd = 1f,
                        defaultValue = 1f,
                        format = { "${(it * 100).toInt()}%" },
                        onValueChange = { previewBlendAlpha = it },
                        onValueChangeFinished = { changed(SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_ALPHA, it) },
                    )
                    GlassSliderPreference(
                        title = stringResource(R.string.glass_blend_lightness),
                        summary = stringResource(R.string.glass_blend_lightness_summary),
                        value = previewBlendLightness,
                        rangeStart = 0f,
                        rangeEnd = 2f,
                        defaultValue = 1f,
                        format = { "${(it * 100).toInt()}%" },
                        onValueChange = { previewBlendLightness = it },
                        onValueChangeFinished = { changed(SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_LIGHTNESS, it) },
                    )
                    GlassSliderPreference(
                        title = stringResource(R.string.glass_blur_radius),
                        summary = stringResource(R.string.glass_blur_radius_summary),
                        value = previewRadiusScale,
                        rangeStart = 0f,
                        rangeEnd = 2f,
                        defaultValue = 1f,
                        format = { "${(it * 100).toInt()}%" },
                        onValueChange = { previewRadiusScale = it },
                        onValueChangeFinished = { changed(SystemUiSettingsKeys.KEY_GLASS_TUNER_RADIUS_SCALE, it) },
                    )
                    GlassSliderPreference(
                        title = stringResource(R.string.glass_glass_opacity),
                        summary = stringResource(R.string.glass_glass_opacity_summary),
                        value = previewGlassOpacity,
                        rangeStart = 0f,
                        rangeEnd = 1f,
                        defaultValue = 1f,
                        format = { "${(it * 100).toInt()}%" },
                        onValueChange = { previewGlassOpacity = it },
                        onValueChangeFinished = { changed(SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_OPACITY, it) },
                    )
                    GlassSliderPreference(
                        title = stringResource(R.string.glass_glass_tone),
                        summary = stringResource(R.string.glass_glass_tone_summary),
                        value = previewGlassTone,
                        rangeStart = 0f,
                        rangeEnd = 2f,
                        defaultValue = 1f,
                        format = { "${(it * 100).toInt()}%" },
                        onValueChange = { previewGlassTone = it },
                        onValueChangeFinished = { changed(SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_TONE, it) },
                    )
                }
            }
            ArrowPreference(
                title = stringResource(R.string.glass_reset),
                summary = stringResource(R.string.glass_reset_summary),
                onClick = {
                    previewBlendAlpha = 1f
                    previewBlendLightness = 1f
                    previewRadiusScale = 1f
                    previewGlassOpacity = 1f
                    previewGlassTone = 1f
                    changed(SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_ALPHA, 1f)
                    changed(SystemUiSettingsKeys.KEY_GLASS_TUNER_BLEND_LIGHTNESS, 1f)
                    changed(SystemUiSettingsKeys.KEY_GLASS_TUNER_RADIUS_SCALE, 1f)
                    changed(SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_OPACITY, 1f)
                    changed(SystemUiSettingsKeys.KEY_GLASS_TUNER_GLASS_TONE, 1f)
                    Toast.makeText(context, R.string.glass_reset_done, Toast.LENGTH_SHORT).show()
                },
            )
        }

        SmallTitle(stringResource(R.string.glass_presets))
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
            Column(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = stringResource(R.string.glass_preset_save),
                    summary = stringResource(R.string.glass_preset_save_summary),
                    onClick = { saveCurrentPreset(suggestPresetName()) },
                )
                ArrowPreference(
                    title = stringResource(R.string.glass_preset_share_current),
                    summary = stringResource(R.string.glass_preset_share_current_summary),
                    onClick = { sharePreset(currentTunerValues()) },
                )
                ArrowPreference(
                    title = stringResource(R.string.glass_preset_clipboard_import),
                    summary = stringResource(R.string.glass_preset_clipboard_import_summary),
                    onClick = ::importPresetFromClipboard,
                )
            }
        }

        SmallTitle(stringResource(R.string.glass_saved_presets))
        if (presets.isEmpty()) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.glass_preset_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                )
            }
        } else {
            presets.forEach { preset ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = preset.name,
                            summary = stringResource(R.string.glass_preset_apply_summary),
                            onClick = {
                                applyTunerValues(preset.values)
                                Toast.makeText(context, R.string.glass_preset_applied, Toast.LENGTH_SHORT).show()
                            },
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                        ) {
                            PresetIconAction(
                                label = stringResource(R.string.glass_preset_rename),
                                icon = MiuixIcons.Edit,
                                onClick = {
                                    DebugLog.i("GlassTuner", "preset rename requested")
                                    renameInput.setTextAndPlaceCursorAtEnd(preset.name)
                                    presetToRename = preset
                                },
                            )
                            PresetIconAction(
                                label = stringResource(R.string.glass_preset_share),
                                icon = MiuixIcons.Share,
                                onClick = { sharePreset(preset.values) },
                            )
                            PresetIconAction(
                                label = stringResource(R.string.glass_preset_delete),
                                icon = MiuixIcons.Delete,
                                onClick = {
                                    DebugLog.i("GlassTuner", "preset delete confirmation requested")
                                    presetToDelete = preset
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val importingPreset = pendingPresetImport
    if (importingPreset != null) {
        AppDialog(
            onDismissRequest = {
                DebugLog.i("GlassTuner", "preset import confirmation cancelled")
                pendingPresetImport = null
            },
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.glass_preset_import_confirm_title),
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.glass_preset_import_confirm_summary),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    TextButton(
                        text = stringResource(R.string.glass_preset_import_apply),
                        onClick = { confirmPresetImport(saveCurrentFirst = false) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        text = stringResource(R.string.glass_preset_save_and_import),
                        onClick = { confirmPresetImport(saveCurrentFirst = true) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    TextButton(
                        text = stringResource(R.string.scale_cancel),
                        onClick = {
                            DebugLog.i("GlassTuner", "preset import confirmation cancelled")
                            pendingPresetImport = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    val deletingPreset = presetToDelete
    if (deletingPreset != null) {
        AppDialog(
            onDismissRequest = {
                DebugLog.i("GlassTuner", "preset delete confirmation dismissed")
                presetToDelete = null
            },
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.glass_preset_delete_confirm_title),
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.glass_preset_delete_confirm_summary, deletingPreset.name),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            text = stringResource(R.string.scale_cancel),
                            onClick = {
                                DebugLog.i("GlassTuner", "preset delete confirmation cancelled")
                                presetToDelete = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = stringResource(R.string.glass_preset_delete),
                            onClick = {
                                DebugLog.i("GlassTuner", "preset deletion confirmed")
                                val updated = presets.filterNot { it.name == deletingPreset.name }
                                if (GlassTunerPresetStore.save(presetPreferences, updated)) {
                                    presets = GlassTunerPresetStore.load(presetPreferences)
                                    DebugLog.i("GlassTuner", "preset deleted; count=${presets.size}")
                                    Toast.makeText(context, R.string.glass_preset_deleted, Toast.LENGTH_SHORT).show()
                                    presetToDelete = null
                                } else {
                                    DebugLog.e("GlassTuner", "preset delete failed: SharedsettingsStore.commit returned false")
                                    Toast.makeText(context, R.string.glass_preset_delete_failed, Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        }
    }

    val renamingPreset = presetToRename
    if (renamingPreset != null) {
        AppDialog(
            onDismissRequest = {
                DebugLog.i("GlassTuner", "preset rename dismissed")
                presetToRename = null
            },
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.glass_preset_rename_title),
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    TextField(
                        state = renameInput,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        label = stringResource(R.string.glass_preset_name_label),
                        inputTransformation = InputTransformation.byValue { _, proposed -> proposed.take(40) },
                    )
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            text = stringResource(R.string.scale_cancel),
                            onClick = {
                                DebugLog.i("GlassTuner", "preset rename cancelled")
                                presetToRename = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = stringResource(R.string.glass_preset_rename_confirm),
                            onClick = {
                                DebugLog.i("GlassTuner", "preset rename confirmed")
                                if (renamePreset(renamingPreset, renameInput.text.toString())) presetToRename = null
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            }
        }
    }
}
