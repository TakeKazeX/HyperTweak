package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.ui.effect.GlassPreviewConfig
import com.takekazex.hypertweak.ui.effect.GlassTunerPreset
import com.takekazex.hypertweak.ui.effect.GlassTunerPresetStore
import com.takekazex.hypertweak.ui.effect.GlassTunerValues
import com.takekazex.hypertweak.ui.effect.NativeGlassPreviewView
import com.takekazex.hypertweak.ui.effect.decodeBundledGlassPreviewSample
import com.takekazex.hypertweak.ui.effect.decodeGlassPreviewImage
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.RestartUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
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
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlin.math.abs

/**
 * OS4 material-style glass tuner. Overrides the blur/blend resources behind 清透磨砂 and
 * 柔光玻璃 (Settings → Display → Visual style) with user-tuned values, read by
 * `GlassMaterialHooker` in SystemUI. State is kept locally rather than hoisted into
 * `MainActivity`, so every change reports SystemUI to the shared Home restart dialog; the page
 * also keeps its local restart affordance.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun GlassTunerPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val requestRestartScopes = LocalRestartScopeRequest.current
    val handleRestartedScopes = LocalRestartScopeHandled.current
    var systemUiRestartPending by rememberSaveable { mutableStateOf(false) }

    // Local remember-backed state so toggles update instantly; the remote preference write is
    // async, so reading Preferences directly in composition made switches bounce back.
    val prefs = androidx.compose.runtime.remember { mutableStateMapOf<String, Any>() }

    @Suppress("UNCHECKED_CAST")
    fun <T> pref(key: String, default: T): T {
        val existing = prefs[key]
        if (existing != null) return existing as T
        val stored: T = when (default) {
            is Boolean -> Preferences.getBoolean(key, default as Boolean) as T
            is Float -> Preferences.getFloat(key, default as Float) as T
            else -> default
        }
        prefs[key] = stored as Any
        return stored
    }

    fun changed(key: String, value: Any) {
        DebugLog.i("GlassTuner", "preference changed: $key=$value")
        requestRestartScopes(RestartScopeSelection(systemUi = true))
        prefs[key] = value
        when (value) {
            is Boolean -> Preferences.putBoolean(key, value)
            is Float -> Preferences.putFloat(key, value)
        }
        systemUiRestartPending = true
    }

    val enabled = pref(Preferences.KEY_GLASS_TUNER_ENABLED, false)
    val blendAlpha = pref(Preferences.KEY_GLASS_TUNER_BLEND_ALPHA, 1f)
    val blendLightness = pref(Preferences.KEY_GLASS_TUNER_BLEND_LIGHTNESS, 1f)
    val radiusScale = pref(Preferences.KEY_GLASS_TUNER_RADIUS_SCALE, 1f)
    val glassOpacity = pref(Preferences.KEY_GLASS_TUNER_GLASS_OPACITY, 1f)
    val glassTone = pref(Preferences.KEY_GLASS_TUNER_GLASS_TONE, 1f)

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
    var renameText by remember { mutableStateOf("") }
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
        requestRestartScopes(RestartScopeSelection(systemUi = true))
        prefs[Preferences.KEY_GLASS_TUNER_ENABLED] = values.enabled
        prefs[Preferences.KEY_GLASS_TUNER_BLEND_ALPHA] = values.blendAlpha
        prefs[Preferences.KEY_GLASS_TUNER_BLEND_LIGHTNESS] = values.blendLightness
        prefs[Preferences.KEY_GLASS_TUNER_RADIUS_SCALE] = values.radiusScale
        prefs[Preferences.KEY_GLASS_TUNER_GLASS_OPACITY] = values.glassOpacity
        prefs[Preferences.KEY_GLASS_TUNER_GLASS_TONE] = values.glassTone
        Preferences.putBoolean(Preferences.KEY_GLASS_TUNER_ENABLED, values.enabled)
        Preferences.putFloat(Preferences.KEY_GLASS_TUNER_BLEND_ALPHA, values.blendAlpha)
        Preferences.putFloat(Preferences.KEY_GLASS_TUNER_BLEND_LIGHTNESS, values.blendLightness)
        Preferences.putFloat(Preferences.KEY_GLASS_TUNER_RADIUS_SCALE, values.radiusScale)
        Preferences.putFloat(Preferences.KEY_GLASS_TUNER_GLASS_OPACITY, values.glassOpacity)
        Preferences.putFloat(Preferences.KEY_GLASS_TUNER_GLASS_TONE, values.glassTone)
        previewBlendAlpha = values.blendAlpha
        previewBlendLightness = values.blendLightness
        previewRadiusScale = values.radiusScale
        previewGlassOpacity = values.glassOpacity
        previewGlassTone = values.glassTone
        systemUiRestartPending = true
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
            DebugLog.e("GlassTuner", "preset save failed: SharedPreferences.commit returned false")
            Toast.makeText(context, R.string.glass_preset_save_failed, Toast.LENGTH_LONG).show()
            return false
        }
        presets = GlassTunerPresetStore.load(presetPreferences)
        DebugLog.i("GlassTuner", "preset save succeeded; count=${presets.size}")
        if (announce) {
            Toast.makeText(
                context,
                context.getString(R.string.glass_preset_saved_named, normalizedName),
                Toast.LENGTH_SHORT,
            ).show()
        }
        return true
    }

    fun suggestPresetName(): String {
        var index = 1
        while (index <= GlassTunerPresetStore.MAX_PRESETS) {
            val candidate = context.getString(R.string.glass_preset_default_name, index)
            if (presets.none { it.name.equals(candidate, ignoreCase = true) }) return candidate
            index++
        }
        return context.getString(R.string.glass_preset_default_name, System.currentTimeMillis())
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
            DebugLog.e("GlassTuner", "preset rename failed: SharedPreferences.commit returned false")
            Toast.makeText(context, R.string.glass_preset_rename_failed, Toast.LENGTH_LONG).show()
            return false
        }
        presets = GlassTunerPresetStore.load(presetPreferences)
        DebugLog.i("GlassTuner", "preset renamed; count=${presets.size}")
        Toast.makeText(
            context,
            context.getString(R.string.glass_preset_renamed, newName),
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
            DebugLog.w("GlassTuner", "clipboard preset import rejected: empty clipboard")
            Toast.makeText(context, R.string.glass_preset_clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { GlassTunerPresetStore.parseShareCode(code) }
            .onSuccess {
                pendingPresetImport = it
                DebugLog.i("GlassTuner", "clipboard preset code parsed; awaiting overwrite confirmation")
            }
            .onFailure { error ->
                DebugLog.e("GlassTuner", "clipboard preset import failed validation", error)
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
                ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.glass_preset_share), code))
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, code)
            }
            context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.glass_preset_share)))
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

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.glass_title),
            color = MiuixTheme.colorScheme.background,
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.glass_back)) }
            }
        )
    }, containerColor = MiuixTheme.colorScheme.background) { padding ->
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))

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

            SmallTitle(stringResource(R.string.glass_parameters))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = enabled,
                        onCheckedChange = { changed(Preferences.KEY_GLASS_TUNER_ENABLED, it) },
                        title = stringResource(R.string.glass_custom_parameters),
                        summary = stringResource(R.string.glass_parameters_summary)
                    )
                    AnimatedVisibility(
                        visible = enabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
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
                                onValueChangeFinished = { changed(Preferences.KEY_GLASS_TUNER_BLEND_ALPHA, it) },
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
                                onValueChangeFinished = { changed(Preferences.KEY_GLASS_TUNER_BLEND_LIGHTNESS, it) },
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
                                onValueChangeFinished = { changed(Preferences.KEY_GLASS_TUNER_RADIUS_SCALE, it) },
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
                                onValueChangeFinished = { changed(Preferences.KEY_GLASS_TUNER_GLASS_OPACITY, it) },
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
                                onValueChangeFinished = { changed(Preferences.KEY_GLASS_TUNER_GLASS_TONE, it) },
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
                            changed(Preferences.KEY_GLASS_TUNER_BLEND_ALPHA, 1f)
                            changed(Preferences.KEY_GLASS_TUNER_BLEND_LIGHTNESS, 1f)
                            changed(Preferences.KEY_GLASS_TUNER_RADIUS_SCALE, 1f)
                            changed(Preferences.KEY_GLASS_TUNER_GLASS_OPACITY, 1f)
                            changed(Preferences.KEY_GLASS_TUNER_GLASS_TONE, 1f)
                            Toast.makeText(context, R.string.glass_reset_done, Toast.LENGTH_SHORT).show()
                        },
                    )
                }
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
                                        renameText = preset.name
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

            if (systemUiRestartPending) {
                SmallTitle(stringResource(R.string.glass_apply))
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.glass_restart_systemui),
                        summary = stringResource(R.string.glass_restart_systemui_summary),
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

    val importingPreset = pendingPresetImport
    if (importingPreset != null) {
        Dialog(
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
        Dialog(
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
                                    DebugLog.e("GlassTuner", "preset delete failed: SharedPreferences.commit returned false")
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
        Dialog(
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
                        value = renameText,
                        maxLines = 1,
                        label = stringResource(R.string.glass_preset_name_label),
                        onValueChange = { renameText = it.take(40) },
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
                                if (renamePreset(renamingPreset, renameText)) presetToRename = null
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

@Composable
private fun GlassSliderPreference(
    title: String,
    summary: String,
    value: Float,
    rangeStart: Float,
    rangeEnd: Float,
    defaultValue: Float,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    var expanded by remember(title) { mutableStateOf(false) }
    var showValueDialog by remember(title) { mutableStateOf(false) }
    ArrowPreference(
        title = title,
        summary = summary,
        endActions = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(format(sliderValue), color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                IconButton(
                    onClick = {
                        DebugLog.i("GlassTuner", "numeric editor opened: $title")
                        showValueDialog = true
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        MiuixIcons.Edit,
                        contentDescription = stringResource(R.string.glass_slider_edit_value),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        onClick = {
            if (!expanded) {
                expanded = true
                DebugLog.i("GlassTuner", "numeric editor opened: $title")
                showValueDialog = true
            } else {
                expanded = false
            }
        },
        holdDownState = expanded,
        bottomAction = {
            Slider(
                value = sliderValue.coerceIn(rangeStart, rangeEnd),
                onValueChange = { newValue ->
                    sliderValue = newValue.coerceIn(rangeStart, rangeEnd)
                    onValueChange(sliderValue)
                },
                onValueChangeFinished = { onValueChangeFinished(sliderValue) },
                valueRange = rangeStart..rangeEnd,
                showKeyPoints = true,
                keyPoints = listOf(rangeStart, defaultValue, rangeEnd).distinct(),
                magnetThreshold = 0.01f,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            )
        }
    )
    IntValueDialog(
        show = showValueDialog,
        title = title,
        summary = summary,
        suffix = stringResource(R.string.scale_percent),
        range = (rangeStart * 100).roundToInt()..(rangeEnd * 100).roundToInt(),
        currentValue = { (sliderValue * 100).roundToInt() },
        emptyValue = (sliderValue * 100).roundToInt(),
        onValueConfirmed = { percent ->
            val newValue = percent / 100f
            sliderValue = newValue
            onValueChange(newValue)
            onValueChangeFinished(newValue)
            DebugLog.i("GlassTuner", "numeric editor applied: $title=$percent%")
        },
        onDismissRequest = {
            showValueDialog = false
            // Clear ArrowPreference's pressed/expanded state with the dialog. Otherwise the first
            // tap after closing only collapses the row and a second tap is needed to edit again.
            expanded = false
            DebugLog.i("GlassTuner", "numeric editor dismissed; slider collapsed: $title")
        },
    )
}

@Composable
private fun PresetIconAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        }
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun PreviewEmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(216.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(24.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private fun readMaterialStyle(context: Context): Int = runCatching {
    Settings.Secure.getInt(context.contentResolver, "material_style", 1)
}.getOrDefault(1)
