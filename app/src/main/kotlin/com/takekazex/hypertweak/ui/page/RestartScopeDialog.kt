package com.takekazex.hypertweak.ui.page

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.ScopeManager
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

private fun fallbackAppName(context: Context, packageName: String): String = when (packageName) {
    RestartScopeSelection.PACKAGE_SYSTEM_UI -> context.getString(R.string.restart_scope_system_ui)
    RestartScopeSelection.PACKAGE_MIUI_HOME -> context.getString(R.string.restart_scope_miui_home)
    RestartScopeSelection.PACKAGE_SETTINGS -> context.getString(R.string.restart_scope_settings)
    RestartScopeSelection.PACKAGE_AOD -> context.getString(R.string.restart_scope_aod)
    RestartScopeSelection.PACKAGE_SECURITY_CENTER -> context.getString(R.string.restart_scope_security)
    RestartScopeSelection.PACKAGE_SECURITY_CORE -> context.getString(R.string.restart_scope_security)
    RestartScopeSelection.PACKAGE_SCANNER -> context.getString(R.string.restart_scope_scanner)
    RestartScopeSelection.PACKAGE_MILINK -> context.getString(R.string.restart_scope_milink)
    RestartScopeSelection.PACKAGE_TRUST_SERVICE -> context.getString(R.string.restart_scope_mitrust)
    RestartScopeSelection.PACKAGE_GUARD_PROVIDER -> context.getString(R.string.restart_scope_guard_provider)
    RestartScopeSelection.PACKAGE_BLUETOOTH -> context.getString(R.string.restart_scope_bluetooth)
    RestartScopeSelection.PACKAGE_POWERKEEPER -> context.getString(R.string.restart_scope_powerkeeper)
    RestartScopeSelection.PACKAGE_GMS -> context.getString(R.string.restart_scope_gms)
    RestartScopeSelection.PACKAGE_XMSF -> context.getString(R.string.restart_scope_xmsf)
    RestartScopeSelection.PACKAGE_DOWNLOADS -> context.getString(R.string.restart_scope_downloads)
    RestartScopeSelection.PACKAGE_PHONE -> context.getString(R.string.restart_scope_phone)
    RestartScopeSelection.PACKAGE_XIAOMI_PHONE -> context.getString(R.string.restart_scope_hyperphone)
    else -> packageName
}

@Composable
fun RestartScopeDialog(
    show: Boolean,
    initialSelection: RestartScopeSelection,
    onDismissRequest: () -> Unit,
    onConfirm: (RestartScopeSelection) -> Unit
) {
    val context = LocalContext.current
    val service by XposedServiceManager.serviceFlow.collectAsState()
    val fallbackScope = remember(context) { ScopeManager.declaredRestartableScope(context) }

    // The service scope is the source of truth. The declared list is only a first-frame fallback
    // while the service binds, so newly added targets (AON, camera, editor, assistant, Google, ...)
    // appear without another app release or a hardcoded dialog update.
    val scopedPackages by produceState(initialValue = fallbackScope, show, service) {
        if (show) {
            value = ScopeManager.restartableScope(context) ?: fallbackScope
        }
    }
    val candidatePackages = remember(scopedPackages, initialSelection) {
        scopedPackages + initialSelection.toPackageSet()
    }
    val scopeApps by produceState(initialValue = emptyList<String>(), candidatePackages, show) {
        if (!show) return@produceState
        value = withContext(Dispatchers.IO) {
            candidatePackages
                .sorted()
        }
    }

    var selectedPackages by remember(show, initialSelection) {
        mutableStateOf(initialSelection.toPackageSet())
    }

    OverlayDialog(
        show = show,
        title = stringResource(R.string.restart_scoped_apps_title),
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
                        text = stringResource(R.string.restart_detected_tweaks_note),
                        color = MiuixTheme.colorScheme.onPrimaryContainer,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            if (scopeApps.isNotEmpty()) {
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
                        items(scopeApps, key = { it }) { pkg ->
                            AppRestartPreference(
                                packageName = pkg,
                                checked = pkg in selectedPackages,
                                onCheckedChange = { checked ->
                                    selectedPackages = if (checked) {
                                        selectedPackages + pkg
                                    } else {
                                        selectedPackages - pkg
                                    }
                                }
                            )
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.restart_scope_no_apps),
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    text = stringResource(R.string.restart_button),
                    onClick = {
                        onConfirm(RestartScopeSelection.fromPackageSet(selectedPackages))
                        onDismissRequest()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                TextButton(
                    text = stringResource(R.string.restart_cancel),
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
    val appName by produceState(initialValue = fallbackAppName(context, packageName), packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(fallbackAppName(context, packageName))
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
