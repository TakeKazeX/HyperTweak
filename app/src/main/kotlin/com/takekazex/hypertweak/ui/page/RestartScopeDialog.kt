package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun RestartScopeDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (systemUi: Boolean, settings: Boolean, aod: Boolean, securityCenter: Boolean, scanner: Boolean) -> Unit
) {
    var systemUiChecked by remember(show) { mutableStateOf(false) }
    var settingsChecked by remember(show) { mutableStateOf(false) }
    var aodChecked by remember(show) { mutableStateOf(false) }
    var securityCenterChecked by remember(show) { mutableStateOf(false) }
    var scannerChecked by remember(show) { mutableStateOf(false) }

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
                AppRestartRow(
                    packageName = "com.android.systemui",
                    checked = systemUiChecked,
                    onCheckedChange = { systemUiChecked = !systemUiChecked }
                )

                AppRestartRow(
                    packageName = "com.android.settings",
                    checked = settingsChecked,
                    onCheckedChange = { settingsChecked = !settingsChecked }
                )

                AppRestartRow(
                    packageName = "com.miui.aod",
                    checked = aodChecked,
                    onCheckedChange = { aodChecked = !aodChecked }
                )

                AppRestartRow(
                    packageName = "com.miui.securitycenter",
                    checked = securityCenterChecked,
                    onCheckedChange = { securityCenterChecked = !securityCenterChecked }
                )

                AppRestartRow(
                    packageName = "com.xiaomi.scanner",
                    checked = scannerChecked,
                    onCheckedChange = { scannerChecked = !scannerChecked }
                )
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
                        onConfirm(systemUiChecked, settingsChecked, aodChecked, securityCenterChecked, scannerChecked)
                        onDismissRequest()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    )
}

@Composable
fun AppRestartRow(
    packageName: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val packageManager = context.packageManager

    val appInfo = remember(packageName) {
        try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
    }

    val appName = remember(appInfo, packageName) {
        if (appInfo != null) {
            try {
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                when (packageName) {
                    "com.android.systemui" -> "System UI"
                    "com.android.settings" -> "Settings"
                    "com.miui.aod" -> "Always-On Display"
                    "com.miui.securitycenter" -> "Security"
                    "com.xiaomi.scanner" -> "Scanner"
                    else -> packageName
                }
            }
        } else {
            when (packageName) {
                "com.android.systemui" -> "System UI"
                "com.android.settings" -> "Settings"
                "com.miui.aod" -> "Always-On Display"
                "com.miui.securitycenter" -> "Security"
                "com.xiaomi.scanner" -> "Scanner"
                else -> packageName
            }
        }
    }

    val appIcon = remember(packageName) {
        try {
            val drawable = packageManager.getApplicationIcon(packageName)
            drawable.toBitmap(120, 120).asImageBitmap()
        } catch (e: Exception) {
            try {
                val drawable = packageManager.defaultActivityIcon
                drawable.toBitmap(120, 120).asImageBitmap()
            } catch (e2: Exception) {
                null
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange() }
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (appIcon != null) {
            Image(
                bitmap = appIcon,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .padding(end = 12.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .padding(end = 12.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = appName,
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = packageName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }

        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = { onCheckedChange() }
        )
    }
}
