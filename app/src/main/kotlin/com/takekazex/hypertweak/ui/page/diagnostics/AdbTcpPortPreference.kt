package com.takekazex.hypertweak.ui.page.diagnostics

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.util.platform.AdbTcpPort
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun AdbTcpPortPreference() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var listening by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        listening = AdbTcpPort.read()
        busy = false
    }

    SwitchPreference(
        title = stringResource(R.string.debug_adb_tcp_title),
        summary = stringResource(when {
            busy -> R.string.debug_adb_tcp_working
            listening == null -> R.string.debug_adb_tcp_unknown
            else -> R.string.debug_adb_tcp_summary
        }),
        checked = listening == true,
        enabled = !busy,
        onCheckedChange = { enabled ->
            busy = true
            scope.launch {
                val result = AdbTcpPort.setEnabled(enabled)
                listening = result ?: AdbTcpPort.read()
                busy = false
                if (result != enabled) {
                    Toast.makeText(context, R.string.debug_adb_tcp_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    )
}
