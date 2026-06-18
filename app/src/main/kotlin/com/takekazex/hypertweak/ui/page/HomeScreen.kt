package com.takekazex.hypertweak.ui.page

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import com.takekazex.hypertweak.hook.HotReloadReport
import com.takekazex.hypertweak.util.DebugLog
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import com.takekazex.hypertweak.ui.effect.rememberContentReady

@Composable
fun HomeScreenContent(
    padding: PaddingValues,
    moduleActive: Boolean,
    hotReloadAvailable: Boolean,
    hotReloading: Boolean,
    hotReloadTargets: List<String>,
    hotReloadReport: HotReloadReport?,
    packageName: String,
    targetSdk: Int,
    backdrop: LayerBackdrop,
    onNavigateToHiddenFeatures: () -> Unit,
    onHotReload: (restartAllScopes: Boolean) -> Unit,
    onRestartScope: (systemUi: Boolean, settings: Boolean, aod: Boolean, securityCenter: Boolean, scanner: Boolean, milink: Boolean, bluetooth: Boolean) -> Unit
) {
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
        hotReloadAvailable -> "Hot reload required"
        moduleActive -> "Module is ACTIVE"
        else -> "Module is NOT ACTIVE"
    }
    val summaryText = when {
        hotReloadAvailable -> "The module is active, but running hooked processes are still using the previous code. Tap to hot reload with libxposed API 102."
        moduleActive -> "Native libxposed module loaded successfully."
        else -> "Please enable the module in LSPosed manager, ensure 'HyperTweak' itself is checked in the scope, and reboot or restart SystemUI."
    }

    val textContentColor = MiuixTheme.colorScheme.onSurface
    val descTextColor = textContentColor.copy(alpha = 0.8f)

    val contentReady = rememberContentReady()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val topBarBackdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    
    var showRestartDialog by remember { mutableStateOf(false) }
    var showHotReloadDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = "HyperTweak",
                modifier = if (contentReady) {
                    Modifier.textureBlur(
                        backdrop = topBarBackdrop,
                        shape = RectangleShape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(blendColors = listOf(
                            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f))
                        ))
                    )
                } else {
                    Modifier
                },
                color = Color.Transparent,
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .then(if (contentReady) Modifier.layerBackdrop(topBarBackdrop) else Modifier)
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
                            val service = com.takekazex.hypertweak.hook.XposedServiceManager.currentService
                            val frameworkDetail = if (service != null) {
                                "${service.frameworkName} ${service.frameworkVersion} (API ${service.apiVersion})"
                            } else {
                                "Xposed Framework"
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
            SmallTitle(text = "Diagnostics Details")

            // Diagnostics Card using BasicComponent
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = "Module Package",
                        summary = packageName
                    )
                    BasicComponent(
                        title = "Target SDK",
                        summary = targetSdk.toString()
                    )
                    BasicComponent(
                        title = "Device System",
                        summary = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                    )
                }
            }

            // Quick Actions
            SmallTitle(text = "Quick Actions")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "Hidden Features",
                        summary = "Quick access to hidden native system settings",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Extension,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "Hidden Features",
                                tint = MiuixTheme.colorScheme.primary
                            )
                        },
                        onClick = onNavigateToHiddenFeatures
                    )
                    ArrowPreference(
                        title = "Restart Scoped Apps",
                        summary = "Restart SystemUI, Settings, and Always-On Display to apply tweaks",
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "Restart Scope",
                                tint = MiuixTheme.colorScheme.primary
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
            onDismissRequest = { showRestartDialog = false },
            onConfirm = onRestartScope
        )
        HotReloadDialog(
            show = showHotReloadDialog,
            hotReloading = hotReloading,
            targets = hotReloadTargets,
            lastReport = hotReloadReport,
            onDismissRequest = { showHotReloadDialog = false },
            onConfirm = { restartAllScopes ->
                showHotReloadDialog = false
                DebugLog.d("HomeScreen", "hot reload confirmed restartAllScopes=$restartAllScopes")
                onHotReload(restartAllScopes)
            }
        )
    }
}

@Composable
private fun HotReloadDialog(
    show: Boolean,
    hotReloading: Boolean,
    targets: List<String>,
    lastReport: HotReloadReport?,
    onDismissRequest: () -> Unit,
    onConfirm: (Boolean) -> Unit
) {
    var restartAllScopes by remember(show) { mutableStateOf(true) }

    OverlayDialog(
        show = show,
        title = "Hot Reload Module",
        onDismissRequest = onDismissRequest,
        content = {
            Text(
                text = "Trigger libxposed API 102 hot reload for stale hooked processes?",
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
            if (targets.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HotReloadTargetsCard(targets)
            }
            if (lastReport != null) {
                Spacer(modifier = Modifier.height(8.dp))
                HotReloadResultCard(lastReport)
            }
            Spacer(modifier = Modifier.height(8.dp))
            SwitchPreference(
                checked = restartAllScopes,
                onCheckedChange = { restartAllScopes = it },
                title = "Restart all scoped apps",
                summary = "Recommended for long-running targets like SystemUI"
            )
            if (!restartAllScopes) {
                val isDark = isSystemInDarkTheme()
                val warningContainer = if (isDark) Color(0xFF3D300F) else Color(0xFFFFF3C4)
                val warningText = if (isDark) Color(0xFFFFD166) else Color(0xFF7A5200)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.defaultColors(
                        color = warningContainer,
                        contentColor = warningText
                    ),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Not restarting scoped apps may leave long-running processes on old hooks, so some hooks may not take effect.",
                        color = warningText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = "Cancel",
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                    enabled = !hotReloading
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = if (hotReloading) "Reloading" else "Reload",
                    onClick = { onConfirm(restartAllScopes) },
                    modifier = Modifier.weight(1f),
                    enabled = !hotReloading,
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

@Composable
private fun HotReloadTargetsCard(targets: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Stale targets",
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = targets.joinToString("\n") { "- ${friendlyProcessName(it)}" },
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun HotReloadResultCard(report: HotReloadReport) {
    val isDark = isSystemInDarkTheme()
    val hasFailure = report.failedCount > 0
    val container = when {
        hasFailure -> if (isDark) Color(0xFF3A1F1F) else Color(0xFFFFECEC)
        report.results.isEmpty() -> if (isDark) Color(0xFF2F2A1B) else Color(0xFFFFF6D9)
        else -> if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    }
    val content = when {
        hasFailure -> if (isDark) Color(0xFFFFB4AB) else Color(0xFF8C1D18)
        report.results.isEmpty() -> if (isDark) Color(0xFFFFD166) else Color(0xFF7A5200)
        else -> if (isDark) Color(0xFF9BE6B3) else Color(0xFF12622D)
    }
    val title = when {
        hasFailure -> "Last hot reload: ${report.succeededCount} succeeded, ${report.failedCount} failed"
        report.results.isEmpty() -> "Last hot reload: no stale targets"
        else -> "Last hot reload: all targets succeeded"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = container, contentColor = content),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                color = content,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            if (report.results.isNotEmpty()) {
                Text(
                    text = report.results.joinToString("\n") { result ->
                        val marker = if (result.succeeded) "OK" else "FAIL"
                        val message = result.message?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                        "$marker ${friendlyProcessName(result.processName)}$message"
                    },
                    color = content.copy(alpha = 0.86f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

private fun friendlyProcessName(processName: String): String {
    return when (processName) {
        "system", "system_server" -> "System Server"
        "com.android.systemui" -> "System UI"
        "com.android.settings" -> "Settings"
        "com.miui.aod" -> "Always-On Display"
        "com.miui.securitycenter" -> "Security"
        "com.xiaomi.scanner" -> "Scanner"
        "com.milink.service" -> "MiLink Service"
        "com.xiaomi.bluetooth" -> "Xiaomi Bluetooth"
        else -> processName
    }
}
