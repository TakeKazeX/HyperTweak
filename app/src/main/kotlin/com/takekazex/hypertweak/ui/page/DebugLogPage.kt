package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.ui.effect.rememberContentReady
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.util.LogRecord
import com.takekazex.hypertweak.util.LogRepository
import com.takekazex.hypertweak.util.LsposedLogReader
import com.takekazex.hypertweak.util.DebugLog
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogFilter(@StringRes val labelRes: Int) {
    All(R.string.logs_filter_all),
    Errors(R.string.logs_filter_errors),
    Warnings(R.string.logs_filter_warnings),
    Hooks(R.string.logs_filter_hooks),
    FailedHooks(R.string.logs_filter_hook_failed)
}

private val logFilters = LogFilter.entries.toList()

private enum class LogLevelOption(@StringRes val labelRes: Int, val priority: Int) {
    Verbose(R.string.logs_level_verbose, android.util.Log.VERBOSE),
    Debug(R.string.logs_level_debug, android.util.Log.DEBUG),
    Info(R.string.logs_level_info, android.util.Log.INFO),
    Warning(R.string.logs_level_warning, android.util.Log.WARN),
    Error(R.string.logs_level_error, android.util.Log.ERROR),
    Silent(R.string.logs_level_silent, android.util.Log.ASSERT + 1)
}

private val logLevelOptions = LogLevelOption.entries.toList()

private fun logLevelFromPriority(priority: Int): LogLevelOption {
    return logLevelOptions.firstOrNull { it.priority == priority } ?: LogLevelOption.Info
}

private typealias DebugLogEntry = LogRecord

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun LogsPage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val contentReady = rememberContentReady()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val topBarBackdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    // Reading (up to ~0.5MB of prefs) and parsing (regex per line + sort) are too heavy for
    // composition on the main thread, so run them once off-thread and show a loading placeholder.
    var entries by remember { mutableStateOf<List<DebugLogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedFilter by rememberSaveable { mutableStateOf(LogFilter.All) }
    var logLevel by remember {
        mutableStateOf(logLevelFromPriority(Preferences.getInt(Preferences.KEY_LOG_LEVEL, DebugLog.DEFAULT_LEVEL)))
    }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var refreshGeneration by remember { mutableStateOf(0) }
    var lsposedStatus by remember { mutableStateOf<LsposedLogReader.Status?>(null) }
    val service by XposedServiceManager.serviceFlow.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(refreshGeneration, service) {
        loading = true
        val snapshot = withContext(Dispatchers.IO) {
            runCatching { LogRepository.read() }.getOrElse {
                DebugLog.e("LogPage", "log snapshot read failed", it)
                LogRepository.Snapshot(emptyList(), LsposedLogReader.Status.FAILED)
            }
        }
        entries = snapshot.records
        lsposedStatus = snapshot.lsposedStatus
        val global = Preferences.getInt(Preferences.KEY_LOG_LEVEL, DebugLog.DEFAULT_LEVEL)
        logLevel = logLevelFromPriority(global)
        loading = false
    }
    val filteredEntries = remember(entries, selectedFilter) {
        entries.filter { entry ->
            when (selectedFilter) {
                LogFilter.All -> true
                LogFilter.Errors -> entry.isError
                LogFilter.Warnings -> entry.isWarning
                LogFilter.Hooks -> entry.isHook
                LogFilter.FailedHooks -> entry.isHookFailed
            }
        }
    }

    val onExport: () -> Unit = {
        coroutineScope.launch {
            val exportText = buildString {
                append("# HyperTweak logs\n")
                append(DebugLog.sessionHeader().trim())
                append("\nLSPosed=$lsposedStatus\nFilter=${selectedFilter.name} shown=${filteredEntries.size}/${entries.size}\n\n")
                append(filteredEntries.joinToString("\n\n", transform = ::formatSingleEntry))
            }
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.filesDir, "logs").apply { check(exists() || mkdirs()) }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val file = File(dir, "hypertweak-logs-$stamp.txt").also { it.writeText(exportText) }
                    File(dir, "latest.txt").writeText(exportText)
                    file.absolutePath
                }.onFailure { DebugLog.e("LogPage", "log export failed", it) }
            }
            exportStatus = saved.fold(
                { context.getString(R.string.logs_export_status, it) },
                { context.getString(R.string.logs_export_failed) }
            )
            saved.getOrNull()?.let { path ->
                runCatching { shareLogFile(context, context.getString(R.string.logs_share_title), File(path)) }
                    .onFailure {
                        DebugLog.e("LogPage", "log sharing failed", it)
                        exportStatus = context.getString(R.string.logs_share_failed)
                    }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.logs_title),
                modifier = if (contentReady) {
                    Modifier.textureBlur(
                        backdrop = topBarBackdrop,
                        shape = RectangleShape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = surfaceColor.copy(alpha = 0.8f))
                            )
                        )
                    )
                } else Modifier,
                color = Color.Transparent,
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.logs_back_content_description)
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .then(if (contentReady) Modifier.layerBackdrop(topBarBackdrop) else Modifier)
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .overScrollVertical(),
            contentPadding = innerPadding
        ) {
            item(key = "overview") {
                Spacer(modifier = Modifier.height(8.dp))
                SmallTitle(text = stringResource(R.string.logs_overview))
                SummaryCard(entries = entries)
                Card(
                    onClick = { if (!loading) refreshGeneration++ },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    BasicComponent(
                        title = stringResource(R.string.logs_refresh),
                        summary = stringResource(when {
                            loading -> R.string.logs_loading_summary
                            lsposedStatus == LsposedLogReader.Status.READY -> R.string.logs_source_ready
                            lsposedStatus == LsposedLogReader.Status.NO_FILES -> R.string.logs_source_no_files
                            lsposedStatus == LsposedLogReader.Status.TIMED_OUT -> R.string.logs_source_timeout
                            lsposedStatus == LsposedLogReader.Status.ROOT_UNAVAILABLE -> R.string.logs_source_root
                            else -> R.string.logs_source_failed
                        })
                    )
                }
            }
            item(key = "options") {
                SmallTitle(text = stringResource(R.string.logs_options))
                FilterCard(
                    selectedFilter = selectedFilter,
                    shownCount = filteredEntries.size,
                    exportStatus = exportStatus,
                    logLevel = logLevel,
                    onSelected = { selectedFilter = it },
                    onLogLevelSelected = {
                        logLevel = it
                        Preferences.putInt(Preferences.KEY_LOG_LEVEL, it.priority)
                    },
                    onExport = onExport
                )
            }
            item(key = "runtime-title") {
                SmallTitle(text = stringResource(R.string.logs_runtime_title, filteredEntries.size))
            }
            if (loading) {
                item(key = "runtime-loading") { LoadingLogCard() }
            } else if (filteredEntries.isEmpty()) {
                item(key = "runtime-empty") { EmptyLogCard() }
            } else {
                items(filteredEntries, key = { it.id }) { entry ->
                    LogEntryCard(
                        entry = entry,
                        onCopy = {
                            copyText(context, context.getString(R.string.logs_copy_label), formatSingleEntry(entry))
                        }
                    )
                }
            }
            item(key = "bottom-spacer") {
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun SummaryCard(entries: List<DebugLogEntry>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        val errorCount = entries.count { it.isError }
        val warningCount = entries.count { it.isWarning }
        val hookOkCount = entries.count { it.event == "HOOK_OK" }
        val hookFailedCount = entries.count { it.event == "HOOK_FAILED" }

        BasicComponent(
            title = stringResource(R.string.logs_entries),
            summary = stringResource(R.string.logs_entries_summary),
            endActions = {
                Text(
                    text = entries.size.toString(),
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        )
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        BasicComponent(
            title = stringResource(R.string.logs_issues),
            summary = stringResource(R.string.logs_issues_summary, errorCount, warningCount),
            endActions = {
                Text(
                    text = (errorCount + warningCount).toString(),
                    color = if (errorCount > 0) levelColor("E") else MiuixTheme.colorScheme.onSurfaceVariantActions,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        )
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        BasicComponent(
            title = stringResource(R.string.logs_filter_hooks),
            summary = stringResource(R.string.logs_hooks_summary, hookOkCount, hookFailedCount),
            endActions = {
                Text(
                    text = hookOkCount.toString(),
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        )
    }
}

@Composable
private fun FilterCard(
    selectedFilter: LogFilter,
    shownCount: Int,
    exportStatus: String?,
    logLevel: LogLevelOption,
    onSelected: (LogFilter) -> Unit,
    onLogLevelSelected: (LogLevelOption) -> Unit,
    onExport: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        OverlayDropdownPreference(
            title = stringResource(R.string.logs_log_level),
            summary = stringResource(R.string.logs_log_level_summary),
            items = logLevelOptions.map { stringResource(it.labelRes) },
            selectedIndex = logLevelOptions.indexOf(logLevel),
            onSelectedIndexChange = { index ->
                logLevelOptions.getOrNull(index)?.let(onLogLevelSelected)
            }
        )
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        OverlayDropdownPreference(
            title = stringResource(R.string.logs_log_filter),
            summary = pluralStringResource(
                R.plurals.logs_filter_summary,
                shownCount,
                shownCount
            ),
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Filter,
                    contentDescription = stringResource(R.string.logs_filter_content_description),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                )
            },
            items = logFilters.map { stringResource(it.labelRes) },
            selectedIndex = logFilters.indexOf(selectedFilter),
            onSelectedIndexChange = { index ->
                logFilters.getOrNull(index)?.let(onSelected)
            }
        )
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        Card(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth(),
            pressFeedbackType = PressFeedbackType.Sink
        ) {
            BasicComponent(
                title = stringResource(R.string.logs_export),
                summary = exportStatus ?: stringResource(R.string.logs_export_prompt)
            )
        }
    }
}

@Composable
private fun LogEntryCard(
    entry: DebugLogEntry,
    onCopy: () -> Unit
) {
    val context = LocalContext.current
    var expanded by rememberSaveable(entry.id) { mutableStateOf(entry.isError || entry.isHookFailed) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = { expanded = !expanded },
        onLongPress = onCopy
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = entry.level,
                            style = MiuixTheme.textStyles.footnote2.copy(fontWeight = FontWeight.Bold),
                            color = levelBadgeColor(entry.level)
                        )
                    }
                    Text(
                        text = entry.scope,
                        style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                        color = if (entry.isError) levelColor("E") else MiuixTheme.colorScheme.onSurfaceContainer
                    )
                }
                Text(
                    text = entry.shortTime(),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions
                )
            }

            Text(
                text = buildPreviewText(context, entry),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.logs_event_pid, "${entry.source} · ${entry.process} · ${entry.event}", entry.pid),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                    if (entry.stack.isNotBlank()) {
                        Text(
                            text = entry.stack,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun levelBadgeColor(level: String): Color {
    return when (level) {
        "E" -> Color(0xFFE5484D)
        "W" -> Color(0xFFE6A700)
        "I" -> Color(0xFF0091EA)
        else -> MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.6f)
    }
}

@Composable
private fun EmptyLogCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        BasicComponent(
            title = stringResource(R.string.logs_empty_title),
            summary = stringResource(R.string.logs_empty_summary)
        )
    }
}

@Composable
private fun LoadingLogCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        BasicComponent(
            title = stringResource(R.string.logs_loading_title),
            summary = stringResource(R.string.logs_loading_summary)
        )
    }
}

@Composable
private fun levelColor(level: String): Color {
    return when (level) {
        "E" -> Color(0xFFE5484D)
        "W" -> Color(0xFFE6A700)
        else -> MiuixTheme.colorScheme.onSurfaceVariantActions
    }
}

@Composable
private fun actionTint(enabled: Boolean): Color {
    return if (enabled) {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    } else {
        MiuixTheme.colorScheme.disabledOnSecondaryVariant
    }
}

private fun buildPreviewText(context: Context, entry: DebugLogEntry): String {
    val event = eventLabel(context, entry)
    val message = entry.message.trim()
    return when {
        message.isBlank() -> event
        message == event -> event
        else -> "$event · $message"
    }
}

private fun formatSingleEntry(entry: DebugLogEntry): String {
    return buildString {
        append("[${entry.level}] ${entry.time} ${entry.source} ${entry.process} PID=${entry.pid} ${entry.scope}")
        append("\n")
        append(entry.message)
        if (entry.stack.isNotBlank()) {
            append("\n")
            append(entry.stack)
        }
    }
}

private fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareLogFile(context: Context, title: String, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, title)
    if (context.findActivity() == null) {
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

private tailrec fun Context.findActivity(): android.app.Activity? {
    return when (this) {
        is android.app.Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun DebugLogEntry.shortTime(): String {
    return time.substringAfter(' ', time)
}

private fun eventLabel(context: Context, entry: DebugLogEntry): String {
    return when (entry.event) {
        "HOOK_OK" -> context.getString(R.string.logs_event_hook_ok)
        "HOOK_FAILED" -> context.getString(R.string.logs_event_hook_failed)
        "HOOK_SKIPPED" -> context.getString(R.string.logs_event_hook_skipped)
        "FAILED" -> context.getString(R.string.logs_event_failed)
        "MISSING" -> context.getString(R.string.logs_event_missing)
        "SKIPPED" -> context.getString(R.string.logs_event_skipped)
        "OK" -> context.getString(R.string.logs_event_ok)
        else -> entry.level
    }
}
