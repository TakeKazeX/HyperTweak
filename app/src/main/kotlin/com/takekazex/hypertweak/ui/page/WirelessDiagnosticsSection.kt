package com.takekazex.hypertweak.ui.page

import com.takekazex.hypertweak.ui.app.LocalAppServices
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.util.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

/** Device properties are authoritative, including after app upgrades or module preference resets. */
@Composable
internal fun WirelessDiagnosticsSection() {
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<WirelessResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmCleanup by remember { mutableStateOf(false) }
    var errorDetails by remember { mutableStateOf<WirelessResult?>(null) }
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value == Lifecycle.State.RESUMED

    fun perform(operation: suspend () -> WirelessResult) {
        if (busy) return
        busy = true
        scope.launch {
            try { result = operation() }
            finally { busy = false }
        }
    }

    LaunchedEffect(resumed) {
        if (resumed) perform { services.wireless.query() }
    }
    val snapshot = result?.snapshot
    val supported = snapshot?.cnss?.supported == true
    SmallTitle(text = stringResource(R.string.wireless_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column {
            if (supported) {
                WirelessLogger.entries.forEach { logger ->
                    val reading = snapshot[logger]
                    val state = stringResource(when (reading.state) {
                        WirelessState.RUNNING -> R.string.wireless_running
                        WirelessState.STOPPED -> R.string.wireless_stopped
                        WirelessState.ABNORMAL -> R.string.wireless_abnormal_hint
                        WirelessState.UNKNOWN -> R.string.wireless_unknown
                        WirelessState.UNSUPPORTED -> R.string.wireless_unsupported
                    })
                    SwitchPreference(
                        checked = reading.enabled ?: false,
                        enabled = !busy && reading.supported && reading.enabled != null,
                        onCheckedChange = { enabled -> perform { services.wireless.setEnabled(logger, enabled) } },
                        title = stringResource(if (logger == WirelessLogger.CNSS) R.string.wireless_cnss_title else R.string.wireless_tcpdump_title),
                        summary = stringResource(if (logger == WirelessLogger.CNSS) R.string.wireless_cnss_summary else R.string.wireless_tcpdump_summary, state),
                        summaryColor = BasicComponentDefaults.summaryColor(
                            if (reading.state == WirelessState.RUNNING || reading.state == WirelessState.ABNORMAL)
                                MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    )
                }
                ArrowPreference(
                    title = stringResource(R.string.wireless_clear),
                    enabled = !busy,
                    endActions = {
                        Text(
                            text = snapshot.sizeKiB?.let {
                                stringResource(R.string.wireless_usage, String.format(Locale.getDefault(), "%.1f", it / 1024.0))
                            } ?: stringResource(R.string.wireless_usage_unknown),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    },
                    onClick = { confirmCleanup = true }
                )
            } else if (snapshot != null) {
                BasicComponent(title = stringResource(R.string.wireless_unsupported))
            }
            result?.failure?.let { failure ->
                ArrowPreference(
                    title = wirelessFailureMessage(failure),
                    titleColor = BasicComponentDefaults.titleColor(MiuixTheme.colorScheme.error),
                    summary = stringResource(R.string.wireless_error_details),
                    onClick = { errorDetails = result }
                )
            }
            BasicComponent(
                title = stringResource(if (busy) R.string.wireless_loading else R.string.wireless_refresh),
                enabled = !busy,
                onClick = { perform { services.wireless.query() } },
                endActions = { if (busy) InfiniteProgressIndicator() }
            )
        }
    }
    Text(
        text = stringResource(R.string.wireless_footer),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
    )

    // Follow Miuix's WindowDialogDemo: equal-width actions and the native dismiss animation.
    AppWindowDialog(
        show = confirmCleanup,
        title = stringResource(R.string.wireless_clear_dialog_title),
        summary = stringResource(R.string.wireless_clear_confirmation) +
            if (snapshot?.let { it.cnss.enabled == true || it.tcpdump.enabled == true ||
                    it.cnss.service == "running" || it.tcpdump.service == "running" } == true)
                "\n" + stringResource(R.string.wireless_clear_running_hint) else "",
        onDismissRequest = { confirmCleanup = false }
    ) {
        val dismiss = LocalDismissState.current
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.wireless_cancel),
                modifier = Modifier.weight(1f),
                onClick = { dismiss?.invoke() }
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.wireless_clear_confirm),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                enabled = !busy,
                onClick = {
                    dismiss?.invoke()
                    perform { services.wireless.clearLogs() }
                }
            )
        }
    }
    AppWindowDialog(
        show = errorDetails != null,
        title = stringResource(R.string.wireless_error_details),
        summary = errorDetails?.failure?.let { wirelessFailureMessage(it) },
        onDismissRequest = { errorDetails = null }
    ) {
        val dismiss = LocalDismissState.current
        errorDetails?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Text(
                text = detail,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()).padding(bottom = 20.dp)
            )
        }
        TextButton(
            text = stringResource(R.string.wireless_done),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = { dismiss?.invoke() }
        )
    }
}

@Composable
private fun wirelessFailureMessage(failure: WirelessFailure): String = stringResource(when (failure) {
    WirelessFailure.NO_ROOT -> R.string.wireless_error_root
    WirelessFailure.QUERY -> R.string.wireless_error_query
    WirelessFailure.SET_PROPERTY -> R.string.wireless_error_prop
    WirelessFailure.SERVICE_TRANSITION -> R.string.wireless_error_service
    WirelessFailure.CLEANUP -> R.string.wireless_error_cleanup
    WirelessFailure.STORAGE -> R.string.wireless_error_storage
})
