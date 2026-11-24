package com.takekazex.hypertweak.ui.page.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.ImageVector
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.settings.contract.HotReloadReport
import com.takekazex.hypertweak.ui.app.LocalAppServices
import com.takekazex.hypertweak.ui.chrome.AppScrollPage
import com.takekazex.hypertweak.ui.chrome.AppSearchAction
import com.takekazex.hypertweak.ui.chrome.AppSection
import com.takekazex.hypertweak.ui.components.dialog.HotReloadDialog
import com.takekazex.hypertweak.ui.components.dialog.RestartScopeDialog
import com.takekazex.hypertweak.ui.theme.StatusTone
import com.takekazex.hypertweak.ui.theme.statusColors
import com.takekazex.hypertweak.util.device.deviceModel
import com.takekazex.hypertweak.util.restart.RestartScopeSelection
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Battery90Charging
import top.yukonga.miuix.kmp.icon.glass.Error
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Update
import top.yukonga.miuix.kmp.icon.glass.Widget
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun HomeScreenContent(
    padding: PaddingValues,
    onNavigateToSearch: (() -> Unit)?,
    moduleActive: Boolean,
    hotReloadAvailable: Boolean,
    hotReloading: Boolean,
    hotReloadTargets: List<String>,
    hotReloadReport: HotReloadReport?,
    pendingRestartScopes: RestartScopeSelection,
    onNavigateToHiddenFeatures: () -> Unit,
    onNavigateToDeviceInfo: () -> Unit,
    onNavigateToBatteryInfo: () -> Unit,
    onHotReload: () -> Unit,
    onRestartAllScopes: () -> Unit,
    onRestartScope: (RestartScopeSelection) -> Unit
) {
    val services = LocalAppServices.current
    val service = services.module.connection.collectAsStateWithLifecycle().value
    val status = statusColors(
        when {
            hotReloadAvailable -> StatusTone.Warning
            moduleActive -> StatusTone.Success
            else -> StatusTone.Error
        }
    )
    val containerColor = status.container
    val statusIcon = when {
        hotReloadAvailable -> MiuixIcons.Glass.Update
        moduleActive -> CheckCircleOutline
        else -> MiuixIcons.Glass.Error
    }
    val statusTint = status.accent
    val titleText = when {
        hotReloadAvailable -> stringResource(R.string.home_status_hot_reload_required)
        moduleActive -> stringResource(R.string.home_status_active)
        else -> stringResource(R.string.home_status_inactive)
    }
    val summaryText = when {
        hotReloadAvailable -> stringResource(R.string.home_summary_hot_reload_required)
        moduleActive -> stringResource(R.string.home_summary_active)
        else -> stringResource(R.string.home_summary_inactive)
    }

    val textContentColor = MiuixTheme.colorScheme.onSurface
    val descTextColor = textContentColor.copy(alpha = 0.8f)


    var showRestartDialog by rememberSaveable { mutableStateOf(false) }
    var showHotReloadDialog by rememberSaveable { mutableStateOf(false) }

    AppScrollPage(
        title = "HyperTweak",
        onBack = null,
        bottomInset = padding.calculateBottomPadding(),
        actions = { onNavigateToSearch?.let { AppSearchAction(onClick = it) } },
    ) {
        Spacer(Modifier.height(8.dp))

        // KernelSU-style status card: always pressable with Tilt feedback, the oversized status
        // glyph cropped by the card's own corner. No outer clip: the press transform has to run on
        // the card itself, otherwise its rotation is cut against a static frame.
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            colors = CardDefaults.defaultColors(color = containerColor, contentColor = textContentColor),
            pressFeedbackType = PressFeedbackType.Tilt,
            showIndication = true,
            onClick = { if (hotReloadAvailable && !hotReloading) showHotReloadDialog = true },
        ) {
            Box(Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.matchParentSize().offset(x = 38.dp, y = 45.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        imageVector = statusIcon,
                        tint = statusTint,
                        modifier = Modifier.size(170.dp),
                        contentDescription = null,
                    )
                }
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(
                        text = titleText,
                        color = textContentColor,
                        fontWeight = FontWeight.Bold,
                        style = MiuixTheme.textStyles.title3,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(text = summaryText, color = descTextColor, style = MiuixTheme.textStyles.body2)
                    if (moduleActive) {
                        Spacer(Modifier.height(36.dp))
                        Text(
                            text = if (service != null) {
                                stringResource(R.string.home_framework_detail, service.frameworkName, service.frameworkVersion, service.apiVersion)
                            } else {
                                stringResource(R.string.home_framework_fallback)
                            },
                            color = descTextColor,
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        AppSection(title = stringResource(R.string.home_diagnostics_title)) {
            BasicComponent(
                title = stringResource(R.string.home_build_version_title),
                summary = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            )
            BasicComponent(
                title = stringResource(R.string.home_device_model_title),
                summary = deviceModel
            )
            BasicComponent(
                title = stringResource(R.string.home_device_system_title),
                summary = stringResource(R.string.home_device_system_summary, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
            )
            BasicComponent(
                title = stringResource(R.string.home_system_version_title),
                summary = Build.VERSION.INCREMENTAL.takeUnless { it.isBlank() || it == Build.UNKNOWN }
                    ?: Build.DISPLAY.takeUnless { it.isBlank() || it == Build.UNKNOWN }
                    ?: stringResource(R.string.battery_unavailable)
            )
        }

        // Quick Actions
        AppSection(title = stringResource(R.string.home_quick_actions)) {
            ArrowPreference(
                title = stringResource(R.string.device_info_title),
                summary = stringResource(R.string.device_info_menu_summary),
                onClick = onNavigateToDeviceInfo
            )
            ArrowPreference(
                title = stringResource(R.string.battery_info_menu_title),
                summary = stringResource(R.string.battery_info_menu_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.Battery90Charging,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.battery_info_menu_title),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = onNavigateToBatteryInfo
            )

            ArrowPreference(
                title = stringResource(R.string.home_hidden_features),
                summary = stringResource(R.string.home_hidden_features_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.Widget,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.home_hidden_features),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = onNavigateToHiddenFeatures
            )
            ArrowPreference(
                title = stringResource(R.string.home_restart_scoped_apps),
                summary = stringResource(R.string.home_restart_scoped_apps_summary),
                startAction = {
                    Icon(
                        imageVector = MiuixIcons.Glass.Refresh,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(R.string.home_restart_scope),
                        tint = MiuixTheme.colorScheme.onSurface
                    )
                },
                onClick = { showRestartDialog = true }
            )
        }


        RestartScopeDialog(
            show = showRestartDialog,
            initialSelection = pendingRestartScopes,
            onDismissRequest = { showRestartDialog = false },
            onConfirm = onRestartScope
        )
        HotReloadDialog(
            show = showHotReloadDialog,
            hotReloading = hotReloading,
            targets = hotReloadTargets,
            lastReport = hotReloadReport,
            onDismissRequest = { showHotReloadDialog = false },
            onHotReload = onHotReload,
            onRestartScopes = onRestartAllScopes
        )
    }
}

internal fun friendlyProcessName(context: android.content.Context, processName: String): String {
    return when (processName) {
        "system", "system_server", "android" -> context.getString(R.string.home_scope_system_server)
        "com.android.systemui" -> context.getString(R.string.home_scope_system_ui)
        "com.android.settings" -> context.getString(R.string.home_scope_settings)
        "com.miui.aod" -> context.getString(R.string.home_scope_aod)
        "com.miui.home" -> context.getString(R.string.home_scope_launcher)
        "com.miui.securitycenter" -> context.getString(R.string.home_scope_security)
        "com.miui.securitycore" -> context.getString(R.string.home_scope_security)
        "com.miui.powerkeeper" -> context.getString(R.string.home_scope_powerkeeper)
        "com.xiaomi.scanner" -> context.getString(R.string.home_scope_scanner)
        "com.milink.service" -> context.getString(R.string.home_scope_milink)
        "com.xiaomi.trustservice" -> context.getString(R.string.home_scope_mitrust)
        "com.miui.guardprovider" -> context.getString(R.string.home_scope_guard_provider)
        "com.android.contacts" -> context.getString(R.string.home_scope_contacts)
        "com.xiaomi.barrage" -> context.getString(R.string.home_scope_barrage)
        "com.android.thememanager" -> context.getString(R.string.home_scope_theme_manager)
        "com.xiaomi.bluetooth" -> context.getString(R.string.home_scope_bluetooth)
        "com.takekazex.hypertweak" -> "HyperTweak"
        else -> processName
    }
}

/** Material Rounded CheckCircleOutline (Apache-2.0), as on KernelSU's status card; Miuix has no circled check. */
private val CheckCircleOutline: ImageVector by lazy {
    ImageVector.Builder("CheckCircleOutline", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8" +
                "s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8zM15.88,8.29L10,14.17l-1.88,-1.88c-0.39,-0.39 -1.02,-0.39" +
                " -1.41,0 -0.39,0.39 -0.39,1.02 0,1.41l2.59,2.59c0.39,0.39 1.02,0.39 1.41,0L17.3,9.7c0.39,-0.39" +
                " 0.39,-1.02 0,-1.41 -0.39,-0.39 -1.03,-0.39 -1.42,0z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}
