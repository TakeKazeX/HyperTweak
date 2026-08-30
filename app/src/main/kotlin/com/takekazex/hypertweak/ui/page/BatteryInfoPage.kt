package com.takekazex.hypertweak.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.util.BatteryInfoReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val REFRESH_MS = 1_000L

@Composable
fun BatteryInfoPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()

    var sections by remember { mutableStateOf<List<BatteryInfoReader.Section>>(emptyList()) }
    var proxyReady by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableStateOf(0) }

    suspend fun load() {
        val (result, ready) = withContext(Dispatchers.Default) {
            val r = runCatching { BatteryInfoReader.read(context) }.getOrDefault(emptyList())
            val rd = runCatching { BatteryInfoReader.hasPrivilegedSnapshot(context) }.getOrDefault(false)
            r to rd
        }
        sections = result
        proxyReady = ready
    }

    LaunchedEffect(refreshTick) {
        load()
    }

    // Auto-refresh while the page is visible so live charging values stay current.
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_MS)
            refreshTick++
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = stringResource(R.string.battery_info_title),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, stringResource(R.string.battery_info_back))
                }
            },
            actions = {
                IconButton(onClick = { refreshTick++ }) {
                    Icon(MiuixIcons.Refresh, stringResource(R.string.battery_info_refresh))
                }
                IconButton(onClick = { copyAll(context, sections) }) {
                    Icon(MiuixIcons.Copy, stringResource(R.string.battery_info_copy))
                }
            }
        )
    }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(padding.calculateTopPadding() + 8.dp))
            if (!proxyReady) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(
                        text = stringResource(R.string.battery_info_proxy_hint),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            sections.forEach { section ->
                SmallTitle(section.title)
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        section.rows.forEachIndexed { index, row ->
                            if (index > 0) {
                                HorizontalDivider(Modifier.padding(start = 16.dp))
                            }
                            BatteryInfoRow(row, onLongPress = { copyRow(context, row) })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + 24.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BatteryInfoRow(row: BatteryInfoReader.Row, onLongPress: () -> Unit) {
    val name = row.label.substringBefore(" · ")
    val key = row.label.substringAfter(" · ", "")
    val isNa = row.value == "不可用" || row.value.startsWith("不可用") || row.value == "无法读取"
    // Long values (e.g. a 33-char serial) get a smaller font so they fit on one line in full.
    val valueSp = when {
        row.value.length >= 32 -> 9.sp
        row.value.length >= 24 -> 10.sp
        row.value.length >= 18 -> 11.sp
        row.value.length >= 14 -> 12.sp
        else -> 15.sp
    }
    val keySp = when {
        key.length >= 16 -> 9.sp
        key.length >= 12 -> 10.sp
        else -> 12.sp
    }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.weight(0.85f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = name,
                style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (key.isNotEmpty()) {
                Text(
                    text = key,
                    style = MiuixTheme.textStyles.footnote2.copy(fontSize = keySp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = row.value,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            style = MiuixTheme.textStyles.body1.copy(fontSize = valueSp),
            color = if (isNa) MiuixTheme.colorScheme.onSurfaceVariantActions else MiuixTheme.colorScheme.onSurfaceContainer,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun copyRow(context: Context, row: BatteryInfoReader.Row) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.battery_info_title), "${row.label}: ${row.value}"))
}

private fun copyAll(context: Context, sections: List<BatteryInfoReader.Section>) {
    val text = buildString {
        appendLine("HyperTweak 电池信息")
        appendLine("导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sections.forEach { section ->
            appendLine()
            appendLine("== ${section.title} ==")
            section.rows.forEach { row -> appendLine("${row.label}: ${row.value}") }
        }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.battery_info_title), text))
}
