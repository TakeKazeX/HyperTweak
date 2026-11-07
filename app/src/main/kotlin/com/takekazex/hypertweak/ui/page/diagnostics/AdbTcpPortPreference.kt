package com.takekazex.hypertweak.ui.page.diagnostics

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.util.platform.AdbTcpPort
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun AdbTcpPortPreference() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.applicationContext.getSharedPreferences("adb_tcp_options", Context.MODE_PRIVATE)
    }
    var state by remember { mutableStateOf<AdbTcpPort.State?>(null) }
    var busy by remember { mutableStateOf(true) }
    var custom by rememberSaveable { mutableStateOf(preferences.getBoolean("custom", false)) }
    var input by rememberSaveable { mutableStateOf(preferences.getString("port", "").orEmpty()) }
    val active = state?.port
    val port = input.toIntOrNull()?.takeIf { it in 1..65535 }

    LaunchedEffect(Unit) {
        state = AdbTcpPort.read()
        busy = false
    }

    fun change(action: suspend () -> AdbTcpPort.Result) {
        busy = true
        scope.launch {
            val result = action()
            state = result.state ?: AdbTcpPort.read()
            busy = false
            if (result.state == null) {
                val message = if (result.occupied) R.string.debug_adb_tcp_occupied else R.string.debug_adb_tcp_failed
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    SwitchPreference(
        title = stringResource(R.string.debug_adb_tcp_title),
        summary = when {
            busy -> stringResource(R.string.debug_adb_tcp_working)
            state == null -> stringResource(R.string.debug_adb_tcp_unknown)
            active != null -> stringResource(R.string.debug_adb_tcp_listening, active)
            else -> stringResource(R.string.debug_adb_tcp_summary)
        },
        checked = active != null,
        enabled = !busy && (active != null || !custom || port != null),
        onCheckedChange = { enabled -> change { AdbTcpPort.setEnabled(enabled, port.takeIf { custom }) } },
    )
    SwitchPreference(
        title = stringResource(R.string.debug_adb_tcp_custom),
        summary = stringResource(R.string.debug_adb_tcp_custom_summary),
        checked = custom,
        enabled = !busy,
        onCheckedChange = {
            custom = it
            preferences.edit().putBoolean("custom", it).apply()
        },
    )
    if (custom) {
        TextField(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            value = input,
            label = stringResource(R.string.debug_adb_tcp_port),
            onValueChange = {
                input = it.filter { digit -> digit in '0'..'9' }.take(5)
                preferences.edit().putString("port", input).apply()
            },
            maxLines = 1,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    SwitchPreference(
        title = stringResource(R.string.debug_adb_tcp_persistent),
        summary = stringResource(R.string.debug_adb_tcp_persistent_summary),
        checked = state?.persistent == true,
        enabled = !busy && (active != null || state?.persistent == true),
        onCheckedChange = { enabled -> change { AdbTcpPort.setPersistent(enabled) } },
    )
}
