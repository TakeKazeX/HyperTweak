package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.util.NotificationBannerBatch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun NotificationBannerBatchSection() {
    val context = LocalContext.current
    val state by NotificationBannerBatch.state.collectAsState()
    var confirmation by rememberSaveable { mutableIntStateOf(0) }
    var waitingForScan by rememberSaveable { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val result = state.result

    fun dismissReport() {
        showReport = false
        showDetails = false
        NotificationBannerBatch.dismissFeedback()
    }
    fun dismissConfirmation() {
        confirmation = 0
        NotificationBannerBatch.dismissFeedback()
    }

    LaunchedEffect(state) {
        if (!state.busy && (state.result != null || state.error != null)) {
            if ((waitingForScan || confirmation == 2) && state.error == null && state.result?.let { it.failed == 0 && it.channels > 0 } == true && !state.applied) {
                confirmation = 2
            } else {
                showDetails = false
                showReport = true
            }
            waitingForScan = false
        }
    }
    // A saved confirmation must not authorize a write after process death discarded its scan.
    LaunchedEffect(confirmation, state) {
        if (confirmation == 2 && (state.result == null || state.applied || state.busy)) confirmation = 0
    }

    SmallTitle(stringResource(R.string.notification_batch_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        ArrowPreference(
            title = stringResource(R.string.notification_batch_enable),
            summary = stringResource(if (state.busy) R.string.notification_batch_busy else R.string.notification_batch_summary),
            enabled = !state.busy,
            onClick = { confirmation = 1 },
            endActions = { if (state.busy) InfiniteProgressIndicator() }
        )
    }

    val preview = result?.let {
        stringResource(if (state.applied) R.string.notification_batch_result else R.string.notification_batch_preview,
            it.apps, it.channels, it.skipped, it.failed)
    }
    AppWindowDialog(
        show = confirmation != 0,
        title = stringResource(if (confirmation == 2) R.string.notification_batch_confirm_title else R.string.notification_batch_enable),
        summary = if (confirmation == 2) listOfNotNull(preview, stringResource(R.string.notification_batch_confirm)).joinToString("\n\n")
            else stringResource(R.string.notification_batch_warning),
        onDismissRequest = ::dismissConfirmation
    ) {
        val dismiss = LocalDismissState.current
        Row {
            TextButton(text = stringResource(R.string.settings_cancel), modifier = Modifier.weight(1f), onClick = { dismiss?.invoke() ?: dismissConfirmation() })
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(if (confirmation == 2) R.string.notification_batch_apply else R.string.settings_continue),
                modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary(),
                enabled = !state.busy && (confirmation != 2 || (result != null && result.failed == 0 && result.channels > 0 && !state.applied)),
                onClick = {
                    val apply = confirmation == 2
                    if (apply && (result == null || result.failed != 0 || result.channels == 0 || state.applied)) return@TextButton
                    confirmation = 0
                    waitingForScan = !apply
                    NotificationBannerBatch.start(context, apply)
                }
            )
        }
    }

    val reportTitle = when {
        state.error != null -> R.string.notification_batch_failed_title
        (result?.failed ?: 0) > 0 -> R.string.notification_batch_partial_title
        !state.applied && result?.channels == 0 -> R.string.notification_batch_empty_title
        state.applied -> R.string.notification_batch_done_title
        else -> R.string.notification_batch_scan_title
    }
    val reportSummary = when {
        state.error != null -> stringResource(if (state.applied) R.string.notification_batch_error else R.string.notification_batch_scan_error)
        !state.applied && (result?.failed ?: 0) > 0 -> listOfNotNull(preview, stringResource(R.string.notification_batch_scan_error)).joinToString("\n\n")
        else -> preview
    }
    AppWindowDialog(
        show = showReport,
        title = stringResource(reportTitle),
        summary = reportSummary,
        onDismissRequest = ::dismissReport
    ) {
        val dismiss = LocalDismissState.current
        Column {
            if (showDetails && state.error != null) {
                Text(
                    text = state.error.orEmpty(),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()).padding(bottom = 20.dp)
                )
            }
            Row {
                if (state.error != null) {
                    TextButton(
                        text = stringResource(if (showDetails) R.string.notification_batch_hide_details else R.string.notification_batch_details),
                        modifier = Modifier.weight(1f),
                        onClick = { showDetails = !showDetails }
                    )
                    Spacer(Modifier.width(20.dp))
                }
                TextButton(
                    text = stringResource(R.string.notification_batch_close),
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { dismiss?.invoke() ?: dismissReport() }
                )
            }
        }
    }
}
