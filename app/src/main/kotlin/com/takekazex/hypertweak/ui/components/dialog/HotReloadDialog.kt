package com.takekazex.hypertweak.ui.components.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.HotReloadOutcome
import com.takekazex.hypertweak.settings.contract.HotReloadReport
import com.takekazex.hypertweak.settings.contract.NativeUpgradeState
import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.ui.chrome.AppTextButton as TextButton
import com.takekazex.hypertweak.ui.page.home.friendlyProcessName
import com.takekazex.hypertweak.ui.theme.StatusTone
import com.takekazex.hypertweak.ui.theme.statusColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
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
    val services = LocalAppServices.current
    // runningTargets can change after an app update or a process restart without a service-bind
    // callback. Refresh when this diagnostic surface is opened so its list is not a stale cache.
    LaunchedEffect(show) {
        if (show) services.module.refreshHotReloadTargets()
    }

    // An unbound service and "genuinely nothing stale" both produce an empty target list, so the
    // empty state has to name which one it is instead of claiming there is nothing to reload.
    val service by services.module.connection.collectAsStateWithLifecycle()
    val nativeBusy by services.nativeUpgrade.busy.collectAsStateWithLifecycle()
    val nativeReport by services.nativeUpgrade.report.collectAsStateWithLifecycle()
    val runningPackages by services.restarts.runningPackages.collectAsStateWithLifecycle()
    val context = LocalContext.current

    AppWindowDialog(
        show = show,
        title = stringResource(R.string.home_hot_reload_title),
        onDismissRequest = onDismissRequest,
        content = {
            Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text(
                text = stringResource(R.string.home_hot_reload_question),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.body2
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
                        style = MiuixTheme.textStyles.footnote1,
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
                    enabled = !hotReloading && !nativeBusy && runningPackages.isEmpty(),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
                Text(
                    text = stringResource(R.string.native_update_description),
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    style = MiuixTheme.textStyles.footnote1, lineHeight = 18.sp
                )
                TextButton(
                    text = stringResource(if (nativeBusy) R.string.native_update_running else R.string.native_update_action),
                    onClick = { services.nativeUpgrade.activate(context) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !hotReloading && !nativeBusy && runningPackages.isEmpty() && service != null
                )
                nativeReport?.let { report ->
                    Text(text = stringResource(when (report.state) {
                        NativeUpgradeState.CHECKING -> R.string.native_update_running
                        NativeUpgradeState.CURRENT -> R.string.native_update_current
                        NativeUpgradeState.UPDATED -> R.string.native_update_success
                        NativeUpgradeState.FAILED -> R.string.native_update_failed
                        NativeUpgradeState.ROLLBACK_REQUESTED -> R.string.native_update_rollback_requested
                    }), color = MiuixTheme.colorScheme.onSurface, style = MiuixTheme.textStyles.footnote1, lineHeight = 18.sp)
                }
                if (services.nativeUpgrade.canRollback()) {
                    TextButton(text = stringResource(R.string.native_update_rollback),
                        onClick = { services.nativeUpgrade.rollback(context) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !hotReloading && !nativeBusy && runningPackages.isEmpty())
                }
            }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                TextButton(
                    text = stringResource(R.string.home_cancel),
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    enabled = !hotReloading && !nativeBusy && runningPackages.isEmpty(),
                )
                TextButton(
                    text = stringResource(R.string.home_restart_scope),
                    onClick = { onDismissRequest(); onRestartScopes() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    enabled = !hotReloading && !nativeBusy && runningPackages.isEmpty(),
                )
            }
            }
        }
    )
}

@Composable
private fun HotReloadTargetsCard(targets: List<String>) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val listedTargets = remember(targets) { targets.distinct() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.home_stale_targets_title),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.footnote1,
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
                        text = resources.getString(
                            R.string.home_stale_target_line,
                            friendlyProcessName(context, processName)
                        ),
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        style = MiuixTheme.textStyles.footnote1,
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
    val hasFailure = report.failedCount > 0
    val incomplete = report.exitedCount > 0 || report.pendingCount > 0
    val status = statusColors(
        when {
            hasFailure -> StatusTone.Error
            report.results.isEmpty() || incomplete -> StatusTone.Warning
            else -> StatusTone.Success
        }
    )
    val container = status.container
    val content = status.onContainer
    val title = when {
        incomplete -> pluralStringResource(R.plurals.home_hot_reload_result_states, report.pendingCount, report.succeededCount, report.failedCount, report.exitedCount, report.pendingCount)
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
                style = MiuixTheme.textStyles.footnote1,
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
                            style = MiuixTheme.textStyles.footnote1,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}
