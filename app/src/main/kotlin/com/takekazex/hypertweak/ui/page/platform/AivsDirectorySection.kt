package com.takekazex.hypertweak.ui.page.platform

import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.PlatformSettingsKeys
import com.takekazex.hypertweak.ui.app.LocalAppSettingsStore
import com.takekazex.hypertweak.ui.components.dialog.CenteredDialog
import com.takekazex.hypertweak.util.platform.AivsDirectoryMigration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState

@Composable
internal fun AivsDirectorySection() {
    val store = LocalAppSettingsStore.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(store.getBoolean(PlatformSettingsKeys.KEY_AIVS_REDIRECT, false)) }
    var busy by remember { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf(false) }
    var folders by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var checkedEmpty by rememberSaveable { mutableStateOf(false) }
    var moved by rememberSaveable { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val root = remember { Environment.getExternalStorageDirectory().path }
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value == Lifecycle.State.RESUMED
    LaunchedEffect(resumed) {
        if (resumed) enabled = store.getBoolean(PlatformSettingsKeys.KEY_AIVS_REDIRECT, false)
    }
    fun perform(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { error = null; moved = false; checkedEmpty = false; operation() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
            finally { busy = false }
        }
    }
    fun checkDirectories() = perform {
        folders = AivsDirectoryMigration.directories(root).joinToString("\n") { "$root/$it" }
        pending = folders.isNotEmpty()
        checkedEmpty = folders.isEmpty()
    }
    SmallTitle(stringResource(R.string.aivs_section))
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column {
            SwitchPreference(
                checked = enabled,
                enabled = !busy,
                onCheckedChange = { value ->
                    store.putBoolean(PlatformSettingsKeys.KEY_AIVS_REDIRECT, value)
                    enabled = value
                    if (value) checkDirectories()
                },
                title = stringResource(R.string.aivs_redirect_title),
                summary = stringResource(R.string.aivs_redirect_summary)
            )
            if (enabled) ArrowPreference(
                title = stringResource(if (busy) R.string.aivs_working else R.string.aivs_check),
                summary = when {
                    moved -> stringResource(R.string.aivs_moved)
                    checkedEmpty -> stringResource(R.string.aivs_empty)
                    else -> null
                },
                enabled = !busy,
                onClick = { checkDirectories() }
            )
        }
    }
    CenteredDialog(
        show = pending,
        title = stringResource(R.string.aivs_move_title),
        summary = stringResource(R.string.aivs_move_summary, folders),
        onDismissRequest = { pending = false }
    ) {
        val dismiss = LocalDismissState.current
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(text = stringResource(R.string.aivs_skip), modifier = Modifier.weight(1f), onClick = { dismiss?.invoke() })
            TextButton(
                text = stringResource(R.string.aivs_move), modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(), enabled = !busy,
                onClick = {
                    dismiss?.invoke()
                    perform { AivsDirectoryMigration.move(root); moved = true }
                }
            )
        }
    }
    CenteredDialog(
        show = error != null,
        title = stringResource(R.string.aivs_error),
        summary = stringResource(R.string.aivs_error_summary) + "\n" + error.orEmpty(),
        onDismissRequest = { error = null }
    ) {
        val dismiss = LocalDismissState.current
        TextButton(text = stringResource(R.string.wireless_done), modifier = Modifier.fillMaxWidth(), onClick = { dismiss?.invoke() })
    }
}
