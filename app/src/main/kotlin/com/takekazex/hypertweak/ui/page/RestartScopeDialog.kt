package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun RestartScopeDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (systemUi: Boolean, settings: Boolean, aod: Boolean) -> Unit
) {
    var systemUiChecked by remember(show) { mutableStateOf(true) }
    var settingsChecked by remember(show) { mutableStateOf(true) }
    var aodChecked by remember(show) { mutableStateOf(true) }

    OverlayDialog(
        show = show,
        title = "Restart Scoped Apps",
        onDismissRequest = onDismissRequest,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { systemUiChecked = !systemUiChecked }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "System UI")
                    Checkbox(
                        state = if (systemUiChecked) ToggleableState.On else ToggleableState.Off,
                        onClick = { systemUiChecked = !systemUiChecked }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settingsChecked = !settingsChecked }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Settings")
                    Checkbox(
                        state = if (settingsChecked) ToggleableState.On else ToggleableState.Off,
                        onClick = { settingsChecked = !settingsChecked }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { aodChecked = !aodChecked }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Always-On Display")
                    Checkbox(
                        state = if (aodChecked) ToggleableState.On else ToggleableState.Off,
                        onClick = { aodChecked = !aodChecked }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = "Cancel",
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "Restart",
                    onClick = {
                        onConfirm(systemUiChecked, settingsChecked, aodChecked)
                        onDismissRequest()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    )
}
