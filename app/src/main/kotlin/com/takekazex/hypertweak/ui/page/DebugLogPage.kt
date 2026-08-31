package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.ui.effect.rememberContentReady
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
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FIELD_SEPARATOR = "\u001F"

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

@Immutable
private data class DebugLogEntry(
    val index: Int,
    val time: String,
    val level: String,
    val pid: String,
    val scope: String,
    val event: String,
    val message: String,
    val stack: String
) {
    val isError: Boolean = level == "E" || event.contains("FAILED")
    val isWarning: Boolean = level == "W" || event == "MISSING" || event == "SKIPPED" || event == "HOOK_SKIPPED"
    val isHook: Boolean = event.startsWith("HOOK")
    val isHookFailed: Boolean = event == "HOOK_FAILED"
    val id: String = "$index-$time-$pid-$scope"
}

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
    var selectedFilter by remember { mutableStateOf(LogFilter.All) }
    var logLevel by remember {
        mutableStateOf(logLevelFromPriority(Preferences.getInt(Preferences.KEY_LOG_LEVEL, DebugLog.DEFAULT_LEVEL)))
    }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var processLevels by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    LaunchedEffect(Unit) {
        val (parsed, levels) = withContext(Dispatchers.Default) {
            val raw = runCatching { Preferences.getDebugLog() }.getOrDefault("")
            val parsedEntries = parseLogEntries(raw).sortedBy { it.time }.asReversed()
            val tags = runCatching { Preferences.debugLogProcessTags() }.getOrDefault(emptySet())
            val global = Preferences.getInt(Preferences.KEY_LOG_LEVEL, DebugLog.DEFAULT_LEVEL)
            parsedEntries to tags.map { tag -> tag to (Preferences.logLevelFor(tag) ?: global) }
        }
        entries = parsed
        processLevels = levels
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
        val exportText = buildString {
            append("# HyperTweak debug log\n")
            append(DebugLog.sessionHeader().trim())
            append("\nFilter=${selectedFilter.name} shown=${filteredEntries.size}/${entries.size}\n\n")
            append(filteredEntries.joinToString("\n\n", transform = ::formatSingleEntry))
        }
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "hypertweak-logs-$stamp.txt")
        runCatching { file.writeText(exportText) }
        shareText(context, "HyperTweak Logs", exportText)
        exportStatus = context.getString(R.string.logs_export_status, File(dir, "latest.txt").also { runCatching { it.writeText(exportText) } }.absolutePath)
    }

    val onProcessLevelSelected: (String, Int) -> Unit = { tag, level ->
        Preferences.setLogLevelFor(tag, level)
        val global = Preferences.getInt(Preferences.KEY_LOG_LEVEL, DebugLog.DEFAULT_LEVEL)
        processLevels = processLevels.map { (t, _) -> t to (Preferences.logLevelFor(t) ?: global) }
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
            item(key = "process-levels") {
                ProcessLevelsCard(processLevels = processLevels, onSelected = onProcessLevelSelected)
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
            summary = stringResource(R.string.logs_filter_summary, shownCount),
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
private fun ProcessLevelsCard(
    processLevels: List<Pair<String, Int>>,
    onSelected: (String, Int) -> Unit
) {
    if (processLevels.isEmpty()) return
    SmallTitle(text = stringResource(R.string.logs_process_level_title))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        processLevels.forEachIndexed { index, (tag, priority) ->
            if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            OverlayDropdownPreference(
                title = tag,
                summary = stringResource(R.string.logs_process_level_summary),
                items = logLevelOptions.map { stringResource(it.labelRes) },
                selectedIndex = logLevelOptions.indexOf(logLevelFromPriority(priority)).coerceAtLeast(0),
                onSelectedIndexChange = { idx -> logLevelOptions.getOrNull(idx)?.let { onSelected(tag, it.priority) } }
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
                        text = stringResource(R.string.logs_event_pid, entry.event, entry.pid),
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

private fun buildExportText(
    entries: List<DebugLogEntry>,
    filterLabel: String,
    totalCount: Int
): String {
    val header = buildString {
        append("HyperTweak Debug Logs\n")
        append("Filter: $filterLabel\n")
        append("Shown: ${entries.size} / $totalCount\n")
        append("Exported: ${formatExportTime()}\n\n")
    }
    val body = entries.joinToString("\n\n", transform = ::formatSingleEntry)
    return header + body
}

private fun formatSingleEntry(entry: DebugLogEntry): String {
    return buildString {
        append("[${entry.level}] ${entry.time} ${entry.scope}")
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

private fun shareText(context: Context, title: String, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
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

private fun defaultLogFileName(): String {
    return "hyper-tweak-logs-${formatExportStamp()}.txt"
}

private fun formatExportTime(): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}

private fun formatExportStamp(): String {
    return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
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

private fun parseLogEntries(raw: String): List<DebugLogEntry> {
    if (raw.isBlank()) return emptyList()
    val entries = mutableListOf<DebugLogEntry>()
    var pending: DebugLogEntry? = null
    raw.lines().forEach { line ->
        val parsed = parseLogLine(line)
        if (parsed != null) {
            pending?.let(entries::add)
            pending = parsed
        } else if (line.isNotBlank()) {
            val current = pending
            if (current != null) {
                pending = current.copy(
                    stack = listOf(current.stack, line).filter { it.isNotBlank() }.joinToString("\n")
                )
            }
        }
    }
    pending?.let(entries::add)
    return entries.mapIndexed { index, entry -> entry.copy(index = index) }
}

private fun parseLogLine(line: String): DebugLogEntry? {
    if (line.startsWith("v2$FIELD_SEPARATOR")) {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size >= 8) {
            return DebugLogEntry(
                index = 0,
                time = unescape(parts[1]),
                level = unescape(parts[2]),
                pid = parts[3],
                scope = unescape(parts[4]),
                event = unescape(parts[5]),
                message = unescape(parts[6]),
                stack = unescape(parts[7])
            )
        }
    }

    val legacy = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d) ([DWEI])/(\d+) \[(.+?)] (.*)$""")
        .matchEntire(line)
        ?: return null
    val message = legacy.groupValues[5]
    val event = when {
        message.startsWith("HOOK_OK") -> "HOOK_OK"
        message.startsWith("HOOK_FAILED") -> "HOOK_FAILED"
        message.startsWith("HOOK_SKIPPED") -> "HOOK_SKIPPED"
        "failed" in message.lowercase() -> "FAILED"
        "not found" in message.lowercase() -> "MISSING"
        "hooked" in message.lowercase() -> "HOOK_OK"
        else -> "INFO"
    }
    return DebugLogEntry(
        index = 0,
        time = legacy.groupValues[1],
        level = legacy.groupValues[2],
        pid = legacy.groupValues[3],
        scope = legacy.groupValues[4],
        event = event,
        message = message,
        stack = ""
    )
}

private fun unescape(value: String): String {
    val out = StringBuilder(value.length)
    var escaped = false
    value.forEach { ch ->
        if (escaped) {
            out.append(if (ch == 'n') '\n' else ch)
            escaped = false
        } else if (ch == '\\') {
            escaped = true
        } else {
            out.append(ch)
        }
    }
    if (escaped) out.append('\\')
    return out.toString()
}