package com.takekazex.hypertweak.ui.page.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.presentation.launcher.DesktopIconPreviewGeometry
import com.takekazex.hypertweak.settings.contract.LauncherDesktopKeys
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.chrome.AppDropdownPreference as OverlayDropdownPreference
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.dialog.IntValueDialog
import com.takekazex.hypertweak.ui.effect.DesktopIconPreviewView
import com.takekazex.hypertweak.ui.effect.DesktopPreviewIcon
import com.takekazex.hypertweak.ui.state.DesktopGridWriter
import com.takekazex.hypertweak.ui.state.DesktopIconSizeWriter
import com.takekazex.hypertweak.util.platform.PlatformLevel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Icon size saves live; grid drafts reach the launcher only through explicit confirmation. */
@Composable
internal fun DesktopIconSizePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = LocalAppSettingsStore.current
    val services = LocalAppServices.current
    val write by DesktopIconSizeWriter.state.collectAsStateWithLifecycle()
    var saved by rememberSaveable { mutableIntStateOf(store.launcher.desktopIconPercent()) }
    var enabled by rememberSaveable { mutableStateOf((if (write.saving) write.requested else saved) != -1) }
    var seenRevision by remember { mutableLongStateOf(write.revision - if (write.saving) 1 else 0) }
    var progress by rememberSaveable { mutableIntStateOf((if (write.saving) write.requested else saved).let { if (it == -1) DesktopIconPreviewGeometry.DEFAULT_PROGRESS else (it - 50) * 5 }) }
    val gridWrite by DesktopGridWriter.state.collectAsStateWithLifecycle()
    var gridSaved by rememberSaveable { mutableIntStateOf(store.launcher.desktopGrid()) }
    var columns by rememberSaveable { mutableIntStateOf(if (gridSaved == 0) 4 else LauncherDesktopKeys.gridColumns(gridSaved)) }
    var rows by rememberSaveable { mutableIntStateOf(if (gridSaved == 0) 6 else LauncherDesktopKeys.gridRows(gridSaved)) }
    var gridDraftDirty by rememberSaveable { mutableStateOf(false) }
    var folderColumns by rememberSaveable { mutableIntStateOf(store.launcher.openedFolderColumns()) }
    var folderStack by rememberSaveable { mutableStateOf(store.launcher.folderStackPreview()) }
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
    fun cancelGridDraft() {
        gridSaved = store.launcher.desktopGrid()
        val code = gridSaved.takeIf { it in 1..12 }
            ?: gridWrite.status?.observed?.takeIf { it in 1..12 }
        columns = code?.let(LauncherDesktopKeys::gridColumns) ?: 4
        rows = code?.let(LauncherDesktopKeys::gridRows) ?: 6
        gridDraftDirty = false
    }
    fun commitGrid(code: Int) {
        val app = context.applicationContext
        val previous = store.launcher.desktopGrid()
        gridDraftDirty = false
        DesktopGridWriter.submit(code, { value -> store.launcher.commitDesktopGrid(value) },
            { store.launcher.commitDesktopGrid(previous) }, { services.nativeRules.desktopGridStatus(app) })
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        gridSaved = store.launcher.desktopGrid()
        folderColumns = store.launcher.openedFolderColumns()
        folderStack = store.launcher.folderStackPreview()
        DesktopGridWriter.refresh { services.nativeRules.desktopGridStatus(context.applicationContext) }
        if (!write.saving) {
            saved = store.launcher.desktopIconPercent()
            enabled = saved != -1
            progress = if (saved == -1) DesktopIconPreviewGeometry.DEFAULT_PROGRESS else DesktopIconPreviewGeometry.progressOf(saved)
        }
    }
    LaunchedEffect(gridWrite) {
        if (!gridWrite.saving) gridSaved = store.launcher.desktopGrid()
        val host = gridWrite.status
        if (!gridDraftDirty && gridSaved == 0 && host?.restored == true && host.observed in 1..12) {
            columns = LauncherDesktopKeys.gridColumns(host.observed)
            rows = LauncherDesktopKeys.gridRows(host.observed)
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
    AppScrollPage(
        title = stringResource(R.string.desktop_icon_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.icon_back),
    ) {
        AppSection(title = stringResource(R.string.desktop_icon_preview_title)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.desktop_icon_preview_note), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(12.dp))
                AndroidView(factory = { DesktopIconPreviewView(it) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.68f)
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                        .semantics { contentDescription = description },
                    update = { it.update(samples.orEmpty(), columns, rows, if (enabled) progress else 250, foreground) })
                if (samples == null) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.desktop_icon_loading),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        AppSection(title = stringResource(R.string.desktop_icon_size)) {
            SwitchPreference(title = stringResource(R.string.desktop_icon_override),
                summary = stringResource(R.string.desktop_icon_override_summary), checked = enabled,
                onCheckedChange = { enabled = it; submit(if (it) percent else -1) })
            ArrowPreference(title = stringResource(R.string.desktop_icon_preview_percent, previewPercent),
                summary = stringResource(R.string.desktop_icon_size_summary), enabled = enabled, onClick = { editing = true },
                bottomAction = {
                    Slider(value = progress.toFloat(), onValueChange = {
                        progress = it.roundToInt().coerceIn(0, DesktopIconPreviewGeometry.MAX_PROGRESS)
                        submit((DesktopIconPreviewGeometry.relativeScale(progress) * 100).roundToInt())
                    },
                        valueRange = 0f..DesktopIconPreviewGeometry.MAX_PROGRESS.toFloat(), enabled = enabled, showKeyPoints = true, keyPoints = (0..DesktopIconPreviewGeometry.MAX_PROGRESS step 125).map { it.toFloat() }, magnetThreshold = 0.01f,
                        modifier = Modifier.semantics { contentDescription = description })
                })
        }
        AppSection(title = stringResource(R.string.desktop_grid_title)) {
            OverlayDropdownPreference(title = stringResource(R.string.desktop_icon_preview_columns),
                items = (4..6).map { it.toString() }, selectedIndex = columns - 4,
                onSelectedIndexChange = { columns = it + 4; gridDraftDirty = true })
            OverlayDropdownPreference(title = stringResource(R.string.desktop_icon_preview_rows),
                items = (5..8).map { it.toString() }, selectedIndex = rows - 5,
                onSelectedIndexChange = { rows = it + 5; gridDraftDirty = true })
            if (gridWrite.saveFailed || (gridWrite.status?.stage == 7 && gridWrite.status?.requested == gridSaved)) {
                Text(stringResource(R.string.desktop_grid_failed), modifier = Modifier.padding(16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = stringResource(R.string.desktop_grid_cancel),
                    modifier = Modifier.weight(1f), enabled = !gridWrite.saving,
                    onClick = { cancelGridDraft() })
                TextButton(text = stringResource(R.string.desktop_grid_confirm),
                    modifier = Modifier.weight(1f), enabled = !gridWrite.saving,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { commitGrid(LauncherDesktopKeys.gridCode(columns, rows)) })
            }
        }
        if (PlatformLevel.isOs4) {
            LauncherIconThemePreferences()
            AppSection(title = stringResource(R.string.desktop_folders_title)) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.settings_opened_folder_columns_title),
                    summary = stringResource(R.string.settings_opened_folder_columns_summary),
                    items = listOf(
                        stringResource(R.string.settings_opened_folder_columns_default),
                        stringResource(R.string.settings_opened_folder_columns_four),
                        stringResource(R.string.settings_opened_folder_columns_five)
                    ),
                    selectedIndex = (folderColumns - PlatformSettingsKeys.DEFAULT_OPENED_FOLDER_COLUMNS).coerceIn(0, 2),
                    onSelectedIndexChange = { index ->
                        folderColumns = (PlatformSettingsKeys.DEFAULT_OPENED_FOLDER_COLUMNS + index).coerceIn(
                            PlatformSettingsKeys.MIN_OPENED_FOLDER_COLUMNS, PlatformSettingsKeys.MAX_OPENED_FOLDER_COLUMNS)
                        store.putInt(PlatformSettingsKeys.KEY_OPENED_FOLDER_COLUMNS, folderColumns)
                        services.nativeRules.publish(context, store.launcher.hideRecentsClearButton(),
                            openedFolderColumns = folderColumns)
                    })
                SwitchPreference(
                    title = stringResource(R.string.settings_folder_stack_preview_title),
                    summary = stringResource(R.string.settings_folder_stack_preview_summary),
                    checked = folderStack,
                    onCheckedChange = {
                        folderStack = it
                        store.putBoolean(PlatformSettingsKeys.KEY_FOLDER_STACK_PREVIEW, it)
                        services.nativeRules.publish(context, store.launcher.hideRecentsClearButton(), folderStack = it)
                    })
            }
        }
        if (write.revision > 0 && !write.saving && !write.succeeded) {
            Text(stringResource(R.string.desktop_icon_save_failed), modifier = Modifier.padding(24.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton(text = stringResource(R.string.scale_ok), modifier = Modifier.padding(horizontal = 24.dp),
                onClick = { submit(if (enabled) percent else -1) })
        }


        IntValueDialog(show = editing, title = stringResource(R.string.desktop_icon_size),
            summary = stringResource(R.string.desktop_icon_size_summary), suffix = "%", range = LauncherDesktopKeys.MIN_PERCENT..LauncherDesktopKeys.MAX_PERCENT,
            currentValue = { percent }, emptyValue = percent,
            onValueConfirmed = { progress = DesktopIconPreviewGeometry.progressOf(it); submit(it) }, onDismissRequest = { editing = false })
    }

}
