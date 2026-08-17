package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
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
import androidx.compose.runtime.mutableFloatStateOf
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
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * OS4 material-style glass tuner. Overrides the blur/blend resources behind 清透磨砂 and
 * 柔光玻璃 (Settings → Display → Visual style) with user-tuned values, read by
 * `GlassMaterialHooker` in SystemUI. State is kept locally rather than hoisted into
 * `MainActivity`, so these keys do not feed the pending-restart-scope tracking; every change
 * requires a SystemUI restart and the page offers one.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun GlassTunerPage(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
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

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.glass_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.glass_back)) }
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

            SmallTitle(stringResource(R.string.glass_parameters))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = enabled,
                        onCheckedChange = { changed(Preferences.KEY_GLASS_TUNER_ENABLED, it) },
                        title = stringResource(R.string.glass_custom_parameters),
                        summary = stringResource(R.string.glass_parameters_summary)
                    )
                    if (enabled) {
                        SliderRow(
                            title = stringResource(R.string.glass_blend_opacity),
                            value = blendAlpha,
                            rangeStart = 0f,
                            rangeEnd = 1f,
                            format = { "${(it * 100).toInt()}%" }
                        ) {
                            changed(Preferences.KEY_GLASS_TUNER_BLEND_ALPHA, it)
                        }
                        SliderRow(
                            title = stringResource(R.string.glass_blend_lightness),
                            value = blendLightness,
                            rangeStart = 0f,
                            rangeEnd = 2f,
                            format = { "${(it * 100).toInt()}%" }
                        ) {
                            changed(Preferences.KEY_GLASS_TUNER_BLEND_LIGHTNESS, it)
                        }
                        SliderRow(
                            title = stringResource(R.string.glass_blur_radius),
                            value = radiusScale,
                            rangeStart = 0f,
                            rangeEnd = 2f,
                            format = { "${(it * 100).toInt()}%" }
                        ) {
                            changed(Preferences.KEY_GLASS_TUNER_RADIUS_SCALE, it)
                        }
                        SliderRow(
                            title = stringResource(R.string.glass_glass_opacity),
                            value = glassOpacity,
                            rangeStart = 0f,
                            rangeEnd = 1f,
                            format = { "${(it * 100).toInt()}%" }
                        ) {
                            changed(Preferences.KEY_GLASS_TUNER_GLASS_OPACITY, it)
                        }
                        SliderRow(
                            title = stringResource(R.string.glass_glass_tone),
                            value = glassTone,
                            rangeStart = 0f,
                            rangeEnd = 2f,
                            format = { "${(it * 100).toInt()}%" }
                        ) {
                            changed(Preferences.KEY_GLASS_TUNER_GLASS_TONE, it)
                        }
                        Text(
                            text = stringResource(R.string.glass_parameters_help),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            SmallTitle(stringResource(R.string.glass_original_parameters))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                TextButton(
                    text = stringResource(R.string.glass_reset),
                    onClick = {
                        // Only reset the slider values; the master switch stays on and nothing
                        // restarts, so the user can keep tuning after seeing the defaults.
                        changed(Preferences.KEY_GLASS_TUNER_BLEND_ALPHA, 1f)
                        changed(Preferences.KEY_GLASS_TUNER_BLEND_LIGHTNESS, 1f)
                        changed(Preferences.KEY_GLASS_TUNER_RADIUS_SCALE, 1f)
                        changed(Preferences.KEY_GLASS_TUNER_GLASS_OPACITY, 1f)
                        changed(Preferences.KEY_GLASS_TUNER_GLASS_TONE, 1f)
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.glass_reset_done),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
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
                            systemUiRestartPending = false
                        }
                    )
                }
            }

            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    rangeStart: Float,
    rangeEnd: Float,
    format: (Float) -> String,
    onValueChangeFinished: (Float) -> Unit
) {
    // Drag-local value keyed on the incoming value: while dragging it follows the finger, and
    // any external change (e.g. Restore Original Parameters) resets it immediately, so the
    // thumb can never stay stuck on a stale position. The remote preference write is async,
    // so writing on every drag tick would queue dozens of writes and a quick SystemUI restart
    // could start before the daemon has the final value.
    var dragValue by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, modifier = Modifier.weight(1f))
            Text(format(dragValue))
        }
        Slider(
            value = dragValue.coerceIn(rangeStart, rangeEnd),
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                onValueChangeFinished(dragValue.coerceIn(rangeStart, rangeEnd))
            },
            valueRange = rangeStart..rangeEnd
        )
    }
}
