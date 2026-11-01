package com.takekazex.hypertweak.ui.page.home

import com.takekazex.hypertweak.ui.components.dialog.HotReloadDialog
import com.takekazex.hypertweak.ui.components.dialog.RestartScopeDialog
import com.takekazex.hypertweak.ui.app.LocalAppServices
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import com.takekazex.hypertweak.ui.effect.AppScaffold as Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import com.takekazex.hypertweak.ui.effect.AppTopAppBar as TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.settings.contract.HotReloadReport
import com.takekazex.hypertweak.util.restart.RestartScopeSelection
import com.takekazex.hypertweak.util.device.deviceModel
import top.yukonga.miuix.kmp.blur.LayerBackdrop

@Composable
fun HomeScreenContent(
    padding: PaddingValues,
    moduleActive: Boolean,
    hotReloadAvailable: Boolean,
    hotReloading: Boolean,
    hotReloadTargets: List<String>,
    hotReloadReport: HotReloadReport?,
    backdrop: LayerBackdrop,
    pendingRestartScopes: RestartScopeSelection,
    onNavigateToHiddenFeatures: () -> Unit,
    onNavigateToDeviceInfo: () -> Unit,
    onNavigateToBatteryInfo: () -> Unit,
    onHotReload: () -> Unit,
    onRestartAllScopes: () -> Unit,
    onRestartScope: (RestartScopeSelection) -> Unit
) {
    val services = LocalAppServices.current
    val service = services.module.connection.collectAsState().value
    val isDark = isSystemInDarkTheme()
    val containerColor = when {
        hotReloadAvailable -> if (isDark) Color(0xFF3D300F) else Color(0xFFFFF3C4)
        moduleActive -> if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
        else -> if (isDark) Color(0xFF381A1A) else Color(0xFFFAEEEE)
    }
    val statusIcon = when {
        hotReloadAvailable -> Icons.Rounded.WarningAmber
        moduleActive -> Icons.Rounded.CheckCircleOutline
        else -> Icons.Rounded.ErrorOutline
    }
    val statusTint = when {
        hotReloadAvailable -> Color(0xFFFFB300)
        moduleActive -> Color(0xFF36D167)
        else -> Color(0xFFD13636)
    }
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

    val topAppBarScrollBehavior = MiuixScrollBehavior()

    var showRestartDialog by rememberSaveable { mutableStateOf(false) }
    var showHotReloadDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "HyperTweak",
                color = Color.Transparent,
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Spacer(modifier = Modifier.height(innerPadding.calculateTopPadding()))
            Spacer(modifier = Modifier.height(24.dp))

            // Large Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(24.dp)),
                colors = CardDefaults.defaultColors(
                    color = containerColor,
                    contentColor = textContentColor
                ),
                pressFeedbackType = PressFeedbackType.Tilt,
                showIndication = hotReloadAvailable,
                onClick = if (hotReloadAvailable && !hotReloading) {
                    { showHotReloadDialog = true }
                } else {
                    null
                }
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .offset(x = 50.dp, y = 38.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            tint = statusTint,
                            modifier = Modifier.size(170.dp),
                            contentDescription = null
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = titleText,
                            color = textContentColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = summaryText,
                            color = descTextColor,
                            fontSize = 13.sp
                        )
                        if (moduleActive) {
                            Spacer(modifier = Modifier.height(36.dp))
                            val frameworkDetail = if (service != null) {
                                stringResource(R.string.home_framework_detail, service.frameworkName, service.frameworkVersion, service.apiVersion)
                            } else {
                                stringResource(R.string.home_framework_fallback)
                            }
                            Text(
                                text = frameworkDetail,
                                color = descTextColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }


            // SmallTitle - proper 28dp left indent like miuix
            SmallTitle(text = stringResource(R.string.home_diagnostics_title))

            // Diagnostics Card using BasicComponent
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
            }

            // Quick Actions
            SmallTitle(text = stringResource(R.string.home_quick_actions))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                                imageVector = Icons.Rounded.BatteryChargingFull,
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
                                imageVector = Icons.Rounded.Extension,
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
                                imageVector = Icons.Rounded.Refresh,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.home_restart_scope),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        },
                        onClick = { showRestartDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
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
        "com.android.thememanager" -> context.getString(R.string.home_scope_theme_manager)
        "com.xiaomi.bluetooth" -> context.getString(R.string.home_scope_bluetooth)
        "com.takekazex.hypertweak" -> "HyperTweak"
        else -> processName
    }
}
