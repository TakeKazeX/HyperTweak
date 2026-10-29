package com.takekazex.hypertweak.ui.page.launcher

import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.ui.state.DesktopIconSizeWriter
import top.yukonga.miuix.kmp.preference.SwitchPreference
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.presentation.launcher.DesktopIconPreviewGeometry
import com.takekazex.hypertweak.ui.components.dialog.IntValueDialog
import com.takekazex.hypertweak.ui.effect.DesktopIconPreviewView
import com.takekazex.hypertweak.ui.effect.DesktopPreviewIcon
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** Changes update the local preview and submit the latest acknowledged native setting live. */
@Composable
internal fun DesktopIconSizePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scroll = MiuixScrollBehavior()
    val store = LocalAppSettingsStore.current
    val services = LocalAppServices.current
    val write by DesktopIconSizeWriter.state.collectAsState()
    var saved by rememberSaveable { mutableIntStateOf(store.launcher.desktopIconPercent()) }
    var enabled by rememberSaveable { mutableStateOf((if (write.saving) write.requested else saved) != -1) }
    var seenRevision by remember { mutableLongStateOf(write.revision - if (write.saving) 1 else 0) }
    var progress by rememberSaveable { mutableIntStateOf((if (write.saving) write.requested else saved).let { if (it == -1) DesktopIconPreviewGeometry.DEFAULT_PROGRESS else (it - 50) * 5 }) }
    var columns by rememberSaveable { mutableIntStateOf(4) }
    var rows by rememberSaveable { mutableIntStateOf(6) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val percent = (DesktopIconPreviewGeometry.relativeScale(progress) * 100).roundToInt()
    fun submit(value: Int) {
        val appContext = context.applicationContext
        DesktopIconSizeWriter.submit(value) { requested ->
            store.launcher.commitDesktopIconPercent(requested).also { ok ->
                if (ok) runCatching { services.nativeRules.publish(appContext, store.launcher.hideRecentsClearButton()) }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!write.saving) {
            saved = store.launcher.desktopIconPercent()
            enabled = saved != -1
            progress = if (saved == -1) 250 else (saved - 50) * 5
        }
    }
    LaunchedEffect(write.revision, write.saving) {
        if (!write.saving && write.revision > seenRevision) {
            seenRevision = write.revision
            if (write.succeeded) saved = write.requested
        }
    }
    val previewPercent = if (enabled) percent else 100
    val description = stringResource(R.string.desktop_icon_preview_description, columns, rows, previewPercent)
    val foreground = MiuixTheme.colorScheme.onSurface.toArgb()
    val sampleApp = stringResource(R.string.desktop_icon_sample_app)
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    val samples by produceState<List<DesktopPreviewIcon>?>(null, context, locale) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = listOf("com.android.contacts", "com.android.mms", "com.android.browser", "com.android.camera",
                "com.android.settings", "com.miui.securitycenter", "com.xiaomi.scanner", context.packageName)
            val fallback = context.applicationInfo.loadIcon(pm).toBitmap(144, 144)
            packages.map { pkg ->
                runCatching {
                    val info = pm.getApplicationInfo(pkg, 0)
                    DesktopPreviewIcon(info.loadIcon(pm).toBitmap(144, 144), info.loadLabel(pm).toString())
                }.getOrElse { DesktopPreviewIcon(fallback, sampleApp) }
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = stringResource(R.string.desktop_icon_title), scrollBehavior = scroll,
            navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, stringResource(R.string.icon_back)) } })
    }) { padding ->
        Column(Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .nestedScroll(scroll.nestedScrollConnection).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            SmallTitle(stringResource(R.string.desktop_icon_preview_title))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.desktop_icon_preview_note), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(12.dp))
                    AndroidView(factory = { DesktopIconPreviewView(it) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.68f)
                            .background(MiuixTheme.colorScheme.surfaceVariant)
                            .semantics { contentDescription = description },
                        update = { it.update(samples.orEmpty(), columns, rows, if (enabled) progress else 250, foreground) })
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(if (samples == null) R.string.desktop_icon_loading else R.string.desktop_icon_preview_legend),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            SmallTitle(stringResource(R.string.desktop_icon_size))
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SwitchPreference(title = stringResource(R.string.desktop_icon_override),
                    summary = stringResource(R.string.desktop_icon_override_summary), checked = enabled,
                    onCheckedChange = { enabled = it; submit(if (it) percent else -1) })
                ArrowPreference(title = stringResource(R.string.desktop_icon_preview_percent, previewPercent),
                    summary = stringResource(R.string.desktop_icon_size_summary), enabled = enabled, onClick = { editing = true },
                    bottomAction = {
                        Slider(value = progress.toFloat(), onValueChange = {
                            progress = it.roundToInt().coerceIn(0, 500)
                            submit((DesktopIconPreviewGeometry.relativeScale(progress) * 100).roundToInt())
                        },
                            valueRange = 0f..500f, enabled = enabled, showKeyPoints = true, keyPoints = listOf(0f, 250f, 500f),
                            modifier = Modifier.semantics { contentDescription = description })
                    })
                OverlayDropdownPreference(title = stringResource(R.string.desktop_icon_preview_columns),
                    items = (4..6).map { it.toString() }, selectedIndex = columns - 4,
                    onSelectedIndexChange = { columns = it + 4 })
                OverlayDropdownPreference(title = stringResource(R.string.desktop_icon_preview_rows),
                    items = (5..8).map { it.toString() }, selectedIndex = rows - 5,
                    onSelectedIndexChange = { rows = it + 5 })
                ArrowPreference(title = stringResource(R.string.desktop_icon_reset_preview), onClick = {
                    enabled = false; progress = DesktopIconPreviewGeometry.DEFAULT_PROGRESS; columns = 4; rows = 6; submit(-1)
                })
            }
            Text(stringResource(when {
                write.saving -> R.string.desktop_icon_saving
                write.revision > 0 && !write.succeeded -> R.string.desktop_icon_save_failed
                write.revision > 0 && write.succeeded -> R.string.desktop_icon_saved
                else -> R.string.desktop_icon_apply_note
            }), modifier = Modifier.padding(24.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (write.revision > 0 && !write.saving && !write.succeeded) {
                TextButton(text = stringResource(R.string.scale_ok), modifier = Modifier.padding(horizontal = 24.dp),
                    onClick = { submit(if (enabled) percent else -1) })
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }

        IntValueDialog(show = editing, title = stringResource(R.string.desktop_icon_size),
            summary = stringResource(R.string.desktop_icon_size_summary), suffix = "%", range = 50..150,
            currentValue = { percent }, emptyValue = percent,
            onValueConfirmed = { progress = (it - 50) * 5; submit(it) }, onDismissRequest = { editing = false })
    }

}
