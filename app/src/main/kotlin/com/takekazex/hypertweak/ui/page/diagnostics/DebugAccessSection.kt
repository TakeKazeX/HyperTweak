package com.takekazex.hypertweak.ui.page.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.util.diagnostics.DebugAccessSession
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun DebugAccessSection() {
    val context = LocalContext.current
    val collector = LocalAppServices.current.diagnostics
    val collection by collector.state.collectAsState()
    val change by DebugAccessSession.changes.collectAsState()
    var remaining by remember { mutableLongStateOf(DebugAccessSession.remaining(context)) }
    var system by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var accessError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(change) {
        while (true) {
            remaining = DebugAccessSession.remaining(context)
            delay(1000)
        }
    }
    SmallTitle(stringResource(R.string.debug_root_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column {
            SwitchPreference(
                title = stringResource(R.string.debug_root_enable),
                summary = if (remaining > 0) stringResource(R.string.debug_root_remaining, (remaining / 60000 + 1).toInt())
                    else stringResource(R.string.debug_root_summary),
                checked = remaining > 0,
                onCheckedChange = {
                    runCatching { DebugAccessSession.setEnabled(context, it) }
                        .onSuccess { remaining = DebugAccessSession.remaining(context); accessError = null }
                        .onFailure { accessError = it.message }
                }
            )
            accessError?.let { Text(it, Modifier.padding(16.dp)) }
            Text(stringResource(R.string.debug_root_command), Modifier.padding(16.dp))
        }
    }
    SmallTitle(stringResource(R.string.debug_bundle_title))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = stringResource(R.string.debug_bundle_system),
                summary = stringResource(R.string.debug_bundle_privacy),
                checked = system,
                enabled = !collection.running,
                onCheckedChange = { system = it }
            )
            Text(stringResource(R.string.debug_bundle_summary), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Button(onClick = { collector.start(context, system) }, enabled = !collection.running,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(if (collection.running) R.string.debug_bundle_running else R.string.debug_bundle_generate))
            }
            if (collection.running) Button(onClick = collector::cancel,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.debug_bundle_cancel))
            }
            collection.error?.let { Text(it, Modifier.padding(16.dp)) }
            collection.file?.let { file ->
                Text(stringResource(R.string.debug_bundle_ready, file.length() / 1024), Modifier.padding(16.dp))
                Text(stringResource(R.string.debug_bundle_sources, collection.collectedSources, collection.unavailableSources), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Button(onClick = {
                    runCatching { collector.share(context) }.onFailure { accessError = it.message }
                }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.debug_bundle_share))
                }
            }
        }
    }
}
