package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun isPackageInstalled(pm: android.content.pm.PackageManager, packageName: String): Boolean {
    return try {
        pm.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }
}

private fun fallbackAppName(packageName: String): String = when (packageName) {
    "com.android.systemui" -> "System UI"
    "com.miui.home" -> "Miui Home"
    "com.android.settings" -> "Settings"
    "com.miui.aod" -> "Always-On Display"
    "com.miui.securitycenter" -> "Security"
    "com.xiaomi.scanner" -> "Scanner"
    "com.milink.service" -> "MiLink Service"
    "com.xiaomi.bluetooth" -> "Xiaomi Bluetooth"
    "com.miui.powerkeeper" -> "Power Keeper"
    else -> packageName
}

@Composable
fun RestartScopeDialog(
    show: Boolean,
    initialSelection: RestartScopeSelection,
    onDismissRequest: () -> Unit,
    onConfirm: (RestartScopeSelection) -> Unit
) {
    var systemUiChecked by remember(show, initialSelection.systemUi) { mutableStateOf(initialSelection.systemUi) }
    var miuiHomeChecked by remember(show, initialSelection.miuiHome) { mutableStateOf(initialSelection.miuiHome) }
    var settingsChecked by remember(show, initialSelection.settings) { mutableStateOf(initialSelection.settings) }
    var aodChecked by remember(show, initialSelection.aod) { mutableStateOf(initialSelection.aod) }
    var securityCenterChecked by remember(show, initialSelection.securityCenter) { mutableStateOf(initialSelection.securityCenter) }
    var scannerChecked by remember(show, initialSelection.scanner) { mutableStateOf(initialSelection.scanner) }
    var milinkChecked by remember(show, initialSelection.milink) { mutableStateOf(initialSelection.milink) }
    var bluetoothChecked by remember(show, initialSelection.bluetooth) { mutableStateOf(initialSelection.bluetooth) }
    var powerkeeperChecked by remember(show, initialSelection.powerkeeper) { mutableStateOf(initialSelection.powerkeeper) }

    val context = LocalContext.current
    val packageManager = context.packageManager

    // The installed set does not change while the dialog is open, so probe PackageManager once,
    // off the main thread. Keyed on Unit, this no longer re-runs on every open/close edge and
    // never runs the binder I/O in composition (it previously ran even before the dialog showed).
    val installedApps by produceState(initialValue = emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            buildList {
                if (isPackageInstalled(packageManager, "com.android.systemui")) add("com.android.systemui")
                add("com.miui.home")
                if (isPackageInstalled(packageManager, "com.android.settings")) add("com.android.settings")
                if (isPackageInstalled(packageManager, "com.miui.aod")) add("com.miui.aod")
                if (isPackageInstalled(packageManager, "com.miui.securitycenter")) add("com.miui.securitycenter")
                if (isPackageInstalled(packageManager, "com.xiaomi.scanner")) add("com.xiaomi.scanner")
                if (isPackageInstalled(packageManager, "com.milink.service")) add("com.milink.service")
                if (isPackageInstalled(packageManager, "com.xiaomi.bluetooth")) add("com.xiaomi.bluetooth")
                if (isPackageInstalled(packageManager, "com.miui.powerkeeper")) add("com.miui.powerkeeper")
            }
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
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                    ) {
                        items(installedApps, key = { it }) { pkg ->
                            val checked = when (pkg) {
                                "com.android.systemui" -> systemUiChecked
                                "com.miui.home" -> miuiHomeChecked
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
                                    "com.miui.home" -> miuiHomeChecked = newVal
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

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    text = "Restart",
                    onClick = {
                        onConfirm(
                            RestartScopeSelection(
                                systemUi = systemUiChecked,
                                miuiHome = miuiHomeChecked,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = "Cancel",
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
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

    // Label and icon are both PackageManager binder calls, and the icon additionally rasterizes a
    // bitmap; resolve them off the main thread. Seed the label with the offline fallback so the row
    // renders its correct name immediately, then fill in the resolved label and icon once ready.
    val appName by produceState(initialValue = fallbackAppName(packageName), packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(fallbackAppName(packageName))
        }
    }

    val appIcon by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(100, 100).asImageBitmap()
            }.getOrElse {
                runCatching {
                    ContextCompat.getDrawable(context, com.takekazex.hypertweak.R.mipmap.ic_launcher)
                        ?.toBitmap(100, 100)?.asImageBitmap()
                }.getOrNull()
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
            val icon = appIcon
            if (icon != null) {
                Image(
                    bitmap = icon,
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
