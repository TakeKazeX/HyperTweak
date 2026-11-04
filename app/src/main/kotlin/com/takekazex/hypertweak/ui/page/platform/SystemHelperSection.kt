package com.takekazex.hypertweak.ui.page.platform

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.util.platform.SystemHelperFailure
import com.takekazex.hypertweak.util.platform.SystemHelperPackageState
import com.takekazex.hypertweak.util.platform.SystemHelperResult
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Live package state is queried on every entry/resume and after every write; no preference mirror. */
@Composable
internal fun SystemHelperSection() {
    val context = LocalContext.current
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<SystemHelperResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value == Lifecycle.State.RESUMED

    fun perform(operation: suspend () -> SystemHelperResult) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                result = operation()
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(resumed) {
        if (resumed) perform { services.systemHelper.query(context) }
    }

    val state = result?.state
    SmallTitle(text = stringResource(R.string.system_helper_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column {
            SwitchPreference(
                checked = state?.installed == true && !state.enabled,
                enabled = !busy && state?.installed == true,
                onCheckedChange = { disabled ->
                    perform { services.systemHelper.setEnabled(context, !disabled) }
                },
                title = stringResource(R.string.system_helper_disable_title),
                summary = systemHelperSummary(result),
                summaryColor = BasicComponentDefaults.summaryColor(
                    if (result?.failure != null) MiuixTheme.colorScheme.error
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            )
            BasicComponent(
                title = stringResource(if (busy) R.string.system_helper_status_checking else R.string.system_helper_refresh),
                enabled = !busy,
                onClick = { perform { services.systemHelper.query(context) } },
                endActions = { if (busy) InfiniteProgressIndicator() }
            )
        }
    }
    Text(
        text = stringResource(R.string.system_helper_footer),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
    )
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun systemHelperSummary(result: SystemHelperResult?): String {
    val state = result?.state
    val base = when {
        result == null -> stringResource(R.string.system_helper_status_checking)
        state == null || !state.installed -> stringResource(R.string.system_helper_status_not_installed)
        state.enabled -> systemHelperStateLine(state, R.string.system_helper_status_enabled)
        else -> systemHelperStateLine(state, R.string.system_helper_status_disabled)
    }
    return when (result?.failure) {
        SystemHelperFailure.NO_ROOT -> stringResource(R.string.system_helper_status_root, base)
        SystemHelperFailure.SET -> stringResource(R.string.system_helper_status_set_failed, base)
        SystemHelperFailure.QUERY -> stringResource(R.string.system_helper_status_query_failed)
        else -> base
    }
}

@Composable
private fun systemHelperStateLine(state: SystemHelperPackageState, statusRes: Int): String {
    val status = stringResource(statusRes)
    return state.versionName?.let { version ->
        status + " · " + stringResource(R.string.system_helper_version, version)
    } ?: status
}
