package com.takekazex.hypertweak.ui.page

import android.annotation.SuppressLint
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.hook.HotReloadReport
import com.takekazex.hypertweak.hook.XposedServiceManager
import com.takekazex.hypertweak.util.DebugLog
import com.takekazex.hypertweak.util.PlatformLevel
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.ScopeManager
import kotlinx.coroutines.launch
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
    pendingRestartScopes: RestartScopeSelection,
    onNavigateToHiddenFeatures: () -> Unit,
    onHotReload: (restartAllScopes: Boolean) -> Unit,
    onRestartScope: (RestartScopeSelection) -> Unit
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

            ScopeWarningCard()
            Os4LauncherScopeSuggestionCard()

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
                        title = stringResource(R.string.home_diagnostics_module_package),
                        summary = packageName
                    )
                    BasicComponent(
                        title = stringResource(R.string.home_diagnostics_target_sdk),
                        summary = targetSdk.toString()
                    )
                    BasicComponent(
                        title = stringResource(R.string.home_device_system_title),
                        summary = stringResource(R.string.home_device_system_summary, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
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
                        title = stringResource(R.string.home_hidden_features),
                        summary = stringResource(R.string.home_hidden_features_summary),
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Extension,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.home_hidden_features),
                                tint = MiuixTheme.colorScheme.primary
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
        title = stringResource(R.string.home_hot_reload_title),
        onDismissRequest = onDismissRequest,
        content = {
            Text(
                text = stringResource(R.string.home_hot_reload_question),
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
                title = stringResource(R.string.home_hot_reload_restart_all),
                summary = stringResource(R.string.home_hot_reload_restart_all_summary)
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
                        text = stringResource(R.string.home_hot_reload_warning),
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
                    text = stringResource(R.string.home_cancel),
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                    enabled = !hotReloading
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = if (hotReloading) stringResource(R.string.home_reloading) else stringResource(R.string.home_reload),
                    onClick = { onConfirm(restartAllScopes) },
                    modifier = Modifier.weight(1f),
                    enabled = !hotReloading,
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

/**
 * Names the required scopes the user has removed in LSPosed.
 *
 * The module declares `staticScope=false` so it can request scope for input methods at runtime,
 * which also makes the whole list user-editable — a removed entry silently disables whatever
 * depends on it, so surface it rather than letting the feature look broken.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun ScopeWarningCard() {
    val context = LocalContext.current
    val service by XposedServiceManager.serviceFlow.collectAsState()
    val missing by produceState<Set<String>?>(initialValue = null, service) {
        value = if (service == null) null else ScopeManager.missingRequiredScope(context)
    }
    val absent = missing ?: return
    if (absent.isEmpty()) return

    val scope = rememberCoroutineScope()

    SmallTitle(text = stringResource(R.string.home_scope_title))
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            absent.sorted().forEach { packageName ->
                key(packageName) {
                    BasicComponent(
                        title = friendlyProcessName(context, packageName),
                        summary = packageName,
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.WarningAmber,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.home_scope_missing),
                                tint = Color(0xFFFFB300)
                            )
                        }
                    )
                }
            }
            ArrowPreference(
                title = stringResource(R.string.home_scope_restore),
                summary = stringResource(R.string.home_scope_restore_summary),
                onClick = {
                    scope.launch {
                        when (val result = ScopeManager.request(absent)) {
                            is ScopeManager.Result.Failed ->
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            ScopeManager.Result.ServiceUnavailable ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.home_scope_service_unavailable),
                                    Toast.LENGTH_SHORT
                                ).show()
                            else -> Unit
                        }
                    }
                }
            )
        }
    }
}

/**
 * OS4: the launcher is never hooked (its gesture stack is native), so keeping `com.miui.home`
 * in the LSPosed scope only widens the module's footprint. Suggest removing it.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun Os4LauncherScopeSuggestionCard() {
    if (!PlatformLevel.isOs4) return
    val context = LocalContext.current
    val service by XposedServiceManager.serviceFlow.collectAsState()
    // Re-run the scope check after a successful removal so the card disappears in place.
    val refreshKey = remember { mutableStateOf(0) }
    val unneeded by produceState<Set<String>?>(
        initialValue = null,
        service,
        refreshKey.value
    ) {
        value = if (service == null) null else ScopeManager.unneededScope(context)
    }
    val present = unneeded ?: return
    if (present.isEmpty()) return

    val scope = rememberCoroutineScope()

    SmallTitle(text = stringResource(R.string.home_scope_title))
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            present.sorted().forEach { packageName ->
                key(packageName) {
                    BasicComponent(
                        title = friendlyProcessName(context, packageName),
                        summary = packageName,
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(R.string.home_scope_unneeded),
                                tint = Color(0xFF42A5F5)
                            )
                        }
                    )
                }
            }
            ArrowPreference(
                title = stringResource(R.string.home_scope_remove),
                summary = stringResource(R.string.home_scope_remove_summary),
                onClick = {
                    scope.launch {
                        when (val result = ScopeManager.remove(present)) {
                            is ScopeManager.Result.Failed ->
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            ScopeManager.Result.ServiceUnavailable ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.home_scope_service_unavailable),
                                    Toast.LENGTH_SHORT
                                ).show()
                            else -> refreshKey.value++
                        }
                    }
                }
            )
        }
    }
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun HotReloadTargetsCard(targets: List<String>) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.home_stale_targets_title),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = targets.joinToString("\n") {
                    context.getString(R.string.home_stale_target_line, friendlyProcessName(context, it))
                },
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun HotReloadResultCard(report: HotReloadReport) {
    val context = LocalContext.current
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
        hasFailure -> stringResource(R.string.home_hot_reload_result_counts, report.succeededCount, report.failedCount)
        report.results.isEmpty() -> stringResource(R.string.home_hot_reload_result_none)
        else -> stringResource(R.string.home_hot_reload_result_all)
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
                        "$marker ${friendlyProcessName(context, result.processName)}$message"
                    },
                    color = content.copy(alpha = 0.86f),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

private fun friendlyProcessName(context: android.content.Context, processName: String): String {
    return when (processName) {
        "system", "system_server", "android" -> context.getString(R.string.home_scope_system_server)
        "com.android.systemui" -> context.getString(R.string.home_scope_system_ui)
        "com.android.settings" -> context.getString(R.string.home_scope_settings)
        "com.miui.aod" -> context.getString(R.string.home_scope_aod)
        "com.miui.home" -> context.getString(R.string.home_scope_launcher)
        "com.miui.securitycenter" -> context.getString(R.string.home_scope_security)
        "com.miui.powerkeeper" -> context.getString(R.string.home_scope_powerkeeper)
        "com.xiaomi.scanner" -> context.getString(R.string.home_scope_scanner)
        "com.milink.service" -> context.getString(R.string.home_scope_milink)
        "com.xiaomi.bluetooth" -> context.getString(R.string.home_scope_bluetooth)
        "com.takekazex.hypertweak" -> "HyperTweak"
        else -> processName
    }
}
