package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.CheckboxLocation

private fun isPackageInstalled(pm: android.content.pm.PackageManager, packageName: String): Boolean {
    return try {
        pm.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }
}

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

    val context = LocalContext.current
    val packageManager = context.packageManager

    val installedApps = remember(show) {
        buildList {
            if (isPackageInstalled(packageManager, "com.android.systemui")) add("com.android.systemui")
            if (isPackageInstalled(packageManager, "com.android.settings")) add("com.android.settings")
            if (isPackageInstalled(packageManager, "com.miui.aod")) add("com.miui.aod")
            if (isPackageInstalled(packageManager, "com.miui.securitycenter")) add("com.miui.securitycenter")
            if (isPackageInstalled(packageManager, "com.xiaomi.scanner")) add("com.xiaomi.scanner")
        }
    }

    OverlayDialog(
        show = show,
        title = "Restart Scoped Apps",
        onDismissRequest = onDismissRequest,
        content = {
            if (installedApps.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        installedApps.forEach { pkg ->
                            val checked = when (pkg) {
                                "com.android.systemui" -> systemUiChecked
                                "com.android.settings" -> settingsChecked
                                "com.miui.aod" -> aodChecked
                                "com.miui.securitycenter" -> securityCenterChecked
                                "com.xiaomi.scanner" -> scannerChecked
                                else -> false
                            }
                            val onCheckedChange: (Boolean) -> Unit = { newVal ->
                                when (pkg) {
                                    "com.android.systemui" -> systemUiChecked = newVal
                                    "com.android.settings" -> settingsChecked = newVal
                                    "com.miui.aod" -> aodChecked = newVal
                                    "com.miui.securitycenter" -> securityCenterChecked = newVal
                                    "com.xiaomi.scanner" -> scannerChecked = newVal
                                }
                            }
                            AppRestartPreference(
                                packageName = pkg,
                                checked = checked,
                                onCheckedChange = onCheckedChange
                            )
                        }
                    }
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
fun AppRestartPreference(
    packageName: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
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
        fun fallbackName(pkg: String) = when (pkg) {
            "com.android.systemui" -> "System UI"
            "com.android.settings" -> "Settings"
            "com.miui.aod" -> "Always-On Display"
            "com.miui.securitycenter" -> "Security"
            "com.xiaomi.scanner" -> "Scanner"
            else -> pkg
        }
        if (appInfo != null) {
            try {
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                fallbackName(packageName)
            }
        } else {
            fallbackName(packageName)
        }
    }

    val appIcon = remember(packageName) {
        try {
            val drawable = packageManager.getApplicationIcon(packageName)
            drawable.toBitmap(100, 100).asImageBitmap()
        } catch (e: Exception) {
            try {
                val drawable = context.getDrawable(com.takekazex.hypertweak.R.mipmap.ic_launcher)
                drawable?.toBitmap(100, 100)?.asImageBitmap()
            } catch (e2: Exception) {
                null
            }
        }
    }

    CheckboxPreference(
        modifier = modifier,
        title = appName,
        summary = packageName,
        checked = checked,
        onCheckedChange = onCheckedChange,
        checkboxLocation = CheckboxLocation.End,
        startAction = {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Box(
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    )
}
