package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.hook.Preferences
import com.takekazex.hypertweak.util.DebugLog
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val FIELD_SEPARATOR = "\u001F"

private enum class LogFilter(val label: String) {
    All("All"),
    Errors("Errors"),
    Warnings("Warnings"),
    Hooks("Hooks"),
    FailedHooks("Hook Failed")
}

private val logFilters = LogFilter.entries.toList()

@Immutable
private data class DebugLogEntry(
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
}

@Composable
fun DebugLogPage(
    onBack: () -> Unit
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    var logText by remember { mutableStateOf(Preferences.getDebugLog()) }
    var selectedFilter by remember { mutableStateOf(LogFilter.All) }
    val entries = remember(logText) { parseLogEntries(logText).asReversed() }
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

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "Logs",
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            DebugLog.d("DebugLogPage", "logs refreshed from UI")
                            logText = Preferences.getDebugLog()
                        }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = "Refresh",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    }
                    IconButton(
                        onClick = {
                            Preferences.clearDebugLog()
                            DebugLog.d("DebugLogPage", "logs cleared from UI")
                            logText = Preferences.getDebugLog()
                        }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Delete,
                            contentDescription = "Clear",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .overScrollVertical(),
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item("top_spacer") { Spacer(modifier = Modifier.height(8.dp)) }
            item("summary_title") { SmallTitle(text = "Overview") }
            item("summary") {
                SummaryCard(entries = entries)
            }
            item("filters_title") { SmallTitle(text = "Options") }
            item("filters") {
                FilterRow(
                    selectedFilter = selectedFilter,
                    shownCount = filteredEntries.size,
                    onSelected = { selectedFilter = it }
                )
            }
            item("runtime_title") {
                SmallTitle(text = "Runtime (${filteredEntries.size})")
            }
            if (filteredEntries.isEmpty()) {
                item("empty") {
                    EmptyLogCard()
                }
            } else {
                item("runtime") {
                    LogEntriesCard(entries = filteredEntries)
                }
            }
            item("bottom_spacer") { Spacer(modifier = Modifier.height(48.dp)) }
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
            title = "Entries",
            summary = "Runtime records kept by HyperTweak",
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
            title = "Issues",
            summary = "Errors $errorCount · Warnings $warningCount",
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
            title = "Hooks",
            summary = "Succeeded $hookOkCount · Failed $hookFailedCount",
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
private fun FilterRow(
    selectedFilter: LogFilter,
    shownCount: Int,
    onSelected: (LogFilter) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        OverlayDropdownPreference(
            title = "Log Filter",
            summary = "$shownCount records shown",
            items = logFilters.map { it.label },
            selectedIndex = logFilters.indexOf(selectedFilter),
            onSelectedIndexChange = { index ->
                logFilters.getOrNull(index)?.let(onSelected)
            }
        )
    }
}

@Composable
private fun LogEntriesCard(entries: List<DebugLogEntry>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        entries.forEachIndexed { index, entry ->
            LogEntryRow(entry = entry)
            if (index != entries.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: DebugLogEntry) {
    var expanded by remember(entry) { mutableStateOf(entry.isError || entry.isHookFailed) }
    val entryColor = entryColor(entry)

    ArrowPreference(
        title = entry.scope,
        titleColor = BasicComponentDefaults.titleColor(
            color = if (entry.isError) entryColor else MiuixTheme.colorScheme.onBackground
        ),
        summary = buildPreviewText(entry),
        endActions = {
            Text(
                text = entry.shortTime(),
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                fontSize = 11.sp,
                maxLines = 1
            )
        },
        bottomAction = if (expanded) {
            {
                LogEntryDetails(entry = entry)
            }
        } else {
            null
        },
        onClick = { expanded = !expanded },
        holdDownState = expanded
    )
}

@Composable
private fun LogEntryDetails(entry: DebugLogEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 32.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "${entry.event} · PID ${entry.pid} · ${entry.level}",
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = entry.message,
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        if (entry.stack.isNotBlank()) {
            Text(
                text = entry.stack,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace
            )
        }
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
            title = "No logs",
            summary = "No records match the selected filter."
        )
    }
}

@Composable
private fun entryColor(entry: DebugLogEntry): Color {
    return when {
        entry.isError -> levelColor("E")
        entry.isWarning -> levelColor("W")
        entry.event == "HOOK_OK" -> MiuixTheme.colorScheme.onSurfaceVariantActions
        else -> MiuixTheme.colorScheme.onSurfaceVariantActions
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

private fun buildPreviewText(entry: DebugLogEntry): String {
    val event = eventLabel(entry)
    val message = entry.message.trim()
    return when {
        message.isBlank() -> event
        message == event -> event
        else -> "$event · $message"
    }
}

private fun DebugLogEntry.shortTime(): String {
    return time.substringAfter(' ', time)
}

private fun eventLabel(entry: DebugLogEntry): String {
    return when (entry.event) {
        "HOOK_OK" -> "Hook OK"
        "HOOK_FAILED" -> "Hook Failed"
        "HOOK_SKIPPED" -> "Hook Skipped"
        "FAILED" -> "Failed"
        "MISSING" -> "Missing"
        "SKIPPED" -> "Skipped"
        "OK" -> "OK"
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
    return entries
}

private fun parseLogLine(line: String): DebugLogEntry? {
    if (line.startsWith("v2$FIELD_SEPARATOR")) {
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size >= 8) {
            return DebugLogEntry(
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
