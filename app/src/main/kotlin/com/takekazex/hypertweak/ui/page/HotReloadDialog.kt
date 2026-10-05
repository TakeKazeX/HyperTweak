package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.HotReloadOutcome
import com.takekazex.hypertweak.hook.HotReloadReport
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.hook.NativeUpgradeManager
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The two recovery paths are intentionally separate: one exercises libxposed API 102 hot reload,
 * while the other uses HyperTweak's ordinary scoped-process restart broadcast/root path.
 */
@Composable
internal fun HotReloadDialog(
    show: Boolean,
    hotReloading: Boolean,
    targets: List<String>,
    lastReport: HotReloadReport?,
    onDismissRequest: () -> Unit,
    onHotReload: () -> Unit,
    onRestartScopes: () -> Unit
) {
    // runningTargets can change after an app update or a process restart without a service-bind
    // callback. Refresh when this diagnostic surface is opened so its list is not a stale cache.
    LaunchedEffect(show) {
        if (show) XposedServiceManager.refreshHotReloadTargets()
    }

    // An unbound service and "genuinely nothing stale" both produce an empty target list, so the
    // empty state has to name which one it is instead of claiming there is nothing to reload.
    val service by XposedServiceManager.serviceFlow.collectAsState()
    val nativeBusy by NativeUpgradeManager.busy.collectAsState()
    val nativeReport by NativeUpgradeManager.report.collectAsState()
    val context = LocalContext.current
    val contentHeight = (LocalConfiguration.current.screenHeightDp * 0.65f).dp

    OverlayDialog(
        show = show,
        title = stringResource(R.string.home_hot_reload_title),
        onDismissRequest = onDismissRequest,
        content = {
            Column(Modifier.fillMaxWidth().heightIn(max = contentHeight).verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.home_hot_reload_question),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (targets.isNotEmpty()) {
                HotReloadTargetsCard(targets)
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (service == null) R.string.home_hot_reload_checking
                            else R.string.home_hot_reload_no_targets
                        ),
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
            if (lastReport != null) {
                Spacer(modifier = Modifier.height(8.dp))
                HotReloadResultCard(lastReport)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    text = if (hotReloading) {
                        stringResource(R.string.home_reloading)
                    } else {
                        stringResource(R.string.home_reload)
                    },
                    onClick = {
                        onDismissRequest()
                        onHotReload()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !hotReloading && !nativeBusy,
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                Text(
                    text = stringResource(R.string.native_update_description),
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                TextButton(
                    text = stringResource(if (nativeBusy) R.string.native_update_running else R.string.native_update_action),
                    onClick = { NativeUpgradeManager.activate(context) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !hotReloading && !nativeBusy && service != null
                )
                nativeReport?.let { report ->
                    Text(text = stringResource(when (report.state) {
                        NativeUpgradeManager.State.CHECKING -> R.string.native_update_running
                        NativeUpgradeManager.State.CURRENT -> R.string.native_update_current
                        NativeUpgradeManager.State.UPDATED -> R.string.native_update_success
                        NativeUpgradeManager.State.FAILED -> R.string.native_update_failed
                        NativeUpgradeManager.State.ROLLBACK_REQUESTED -> R.string.native_update_rollback_requested
                    }), color = MiuixTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 18.sp)
                }
                if (NativeUpgradeManager.canRollback()) {
                    TextButton(text = stringResource(R.string.native_update_rollback),
                        onClick = { NativeUpgradeManager.rollback(context) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !hotReloading && !nativeBusy)
                }
                TextButton(
                    text = stringResource(R.string.home_restart_scope),
                    onClick = {
                        onDismissRequest()
                        onRestartScopes()
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !hotReloading && !nativeBusy
                )
                TextButton(
                    text = stringResource(R.string.home_cancel),
                    onClick = onDismissRequest,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !hotReloading && !nativeBusy
                )
            }
            }
        }
    )
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun HotReloadTargetsCard(targets: List<String>) {
    val context = LocalContext.current
    val listedTargets = remember(targets) { targets.distinct() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.home_stale_targets_title),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp, max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(listedTargets, key = { it }) { processName ->
                    Text(
                        text = context.getString(
                            R.string.home_stale_target_line,
                            friendlyProcessName(context, processName)
                        ),
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HotReloadResultCard(report: HotReloadReport) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val hasFailure = report.failedCount > 0
    val incomplete = report.exitedCount > 0 || report.pendingCount > 0
    val container = when {
        hasFailure -> if (isDark) Color(0xFF3A1F1F) else Color(0xFFFFECEC)
        (report.results.isEmpty() || incomplete) -> if (isDark) Color(0xFF2F2A1B) else Color(0xFFFFF6D9)
        else -> if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    }
    val content = when {
        hasFailure -> if (isDark) Color(0xFFFFB4AB) else Color(0xFF8C1D18)
        (report.results.isEmpty() || incomplete) -> if (isDark) Color(0xFFFFD166) else Color(0xFF7A5200)
        else -> if (isDark) Color(0xFF9BE6B3) else Color(0xFF12622D)
    }
    val title = when {
        incomplete -> stringResource(R.string.home_hot_reload_result_states, report.succeededCount, report.failedCount, report.exitedCount, report.pendingCount)
        hasFailure -> stringResource(R.string.home_hot_reload_result_counts, report.succeededCount, report.failedCount)
        report.results.isEmpty() -> stringResource(R.string.home_hot_reload_result_none)
        else -> stringResource(R.string.home_hot_reload_result_all)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = container, contentColor = content),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                color = content,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            if (report.results.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(report.results) { _, result ->
                        val marker = when (result.outcome) {
                            HotReloadOutcome.SUCCEEDED -> "OK"
                            HotReloadOutcome.FAILED -> "FAIL"
                            HotReloadOutcome.PROCESS_EXITED -> stringResource(R.string.home_hot_reload_process_exited)
                            HotReloadOutcome.IN_PROGRESS -> stringResource(R.string.home_hot_reload_process_pending)
                        }
                        val message = result.message
                            ?.takeIf { it.isNotBlank() }
                            ?.let { " - $it" }
                            .orEmpty()
                        Text(
                            text = "$marker ${friendlyProcessName(context, result.processName)}$message",
                            color = content.copy(alpha = 0.86f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}
