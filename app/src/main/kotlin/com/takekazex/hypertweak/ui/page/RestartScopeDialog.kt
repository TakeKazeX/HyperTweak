package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.takekazex.hypertweak.util.RestartScopeSelection
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    initialSelection: RestartScopeSelection,
    onDismissRequest: () -> Unit,
    onConfirm: (RestartScopeSelection) -> Unit
) {
    var systemUiChecked by remember(show, initialSelection.systemUi) { mutableStateOf(initialSelection.systemUi) }
    var settingsChecked by remember(show, initialSelection.settings) { mutableStateOf(initialSelection.settings) }
    var aodChecked by remember(show, initialSelection.aod) { mutableStateOf(initialSelection.aod) }
    var securityCenterChecked by remember(show, initialSelection.securityCenter) { mutableStateOf(initialSelection.securityCenter) }
    var scannerChecked by remember(show, initialSelection.scanner) { mutableStateOf(initialSelection.scanner) }
    var milinkChecked by remember(show, initialSelection.milink) { mutableStateOf(initialSelection.milink) }
    var bluetoothChecked by remember(show, initialSelection.bluetooth) { mutableStateOf(initialSelection.bluetooth) }
    var powerkeeperChecked by remember(show, initialSelection.powerkeeper) { mutableStateOf(initialSelection.powerkeeper) }

    val context = LocalContext.current
    val packageManager = context.packageManager

    val installedApps = remember(show) {
        buildList {
            if (isPackageInstalled(packageManager, "com.android.systemui")) add("com.android.systemui")
            if (isPackageInstalled(packageManager, "com.android.settings")) add("com.android.settings")
            if (isPackageInstalled(packageManager, "com.miui.aod")) add("com.miui.aod")
            if (isPackageInstalled(packageManager, "com.miui.securitycenter")) add("com.miui.securitycenter")
            if (isPackageInstalled(packageManager, "com.xiaomi.scanner")) add("com.xiaomi.scanner")
            if (isPackageInstalled(packageManager, "com.milink.service")) add("com.milink.service")
            if (isPackageInstalled(packageManager, "com.xiaomi.bluetooth")) add("com.xiaomi.bluetooth")
            if (isPackageInstalled(packageManager, "com.miui.powerkeeper")) add("com.miui.powerkeeper")
        }
    }

    OverlayDialog(
        show = show,
        title = "Restart Scoped Apps",
        onDismissRequest = onDismissRequest,
        content = {
            if (!initialSelection.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.primaryContainer,
                        contentColor = MiuixTheme.colorScheme.onPrimaryContainer
                    ),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Detected modified tweaks and preselected the related scopes.",
                        color = MiuixTheme.colorScheme.onPrimaryContainer,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

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
                                "com.milink.service" -> milinkChecked
                                "com.xiaomi.bluetooth" -> bluetoothChecked
                                "com.miui.powerkeeper" -> powerkeeperChecked
                                else -> false
                            }
                            val onCheckedChange: (Boolean) -> Unit = { newVal ->
                                when (pkg) {
                                    "com.android.systemui" -> systemUiChecked = newVal
                                    "com.android.settings" -> settingsChecked = newVal
                                    "com.miui.aod" -> aodChecked = newVal
                                    "com.miui.securitycenter" -> securityCenterChecked = newVal
                                    "com.xiaomi.scanner" -> scannerChecked = newVal
                                    "com.milink.service" -> milinkChecked = newVal
                                    "com.xiaomi.bluetooth" -> bluetoothChecked = newVal
                                    "com.miui.powerkeeper" -> powerkeeperChecked = newVal
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
                        onConfirm(
                            RestartScopeSelection(
                                systemUi = systemUiChecked,
                                settings = settingsChecked,
                                aod = aodChecked,
                                securityCenter = securityCenterChecked,
                                scanner = scannerChecked,
                                milink = milinkChecked,
                                bluetooth = bluetoothChecked,
                                powerkeeper = powerkeeperChecked
                            )
                        )
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
            "com.milink.service" -> "MiLink Service"
            "com.xiaomi.bluetooth" -> "Xiaomi Bluetooth"
            "com.miui.powerkeeper" -> "Power Keeper"
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
                val drawable = ContextCompat.getDrawable(context, com.takekazex.hypertweak.R.mipmap.ic_launcher)
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
