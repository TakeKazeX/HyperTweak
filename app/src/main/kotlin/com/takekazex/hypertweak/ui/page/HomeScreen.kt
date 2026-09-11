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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.BatteryChargingFull
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
import com.takekazex.hypertweak.util.RestartScopeSelection
import com.takekazex.hypertweak.util.ScopePrompt
import com.takekazex.hypertweak.util.ScopePromptAction
import com.takekazex.hypertweak.util.ScopePromptStore
import com.takekazex.hypertweak.util.ScopeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
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
    onNavigateToBatteryInfo: () -> Unit,
    onHotReload: () -> Unit,
    onRestartAllScopes: () -> Unit,
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
            LauncherScopeSuggestionCard()

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
    var refreshKey by remember { mutableIntStateOf(0) }
    val missing by produceState<Set<String>?>(initialValue = null, service, refreshKey) {
        value = if (service == null) null else ScopeManager.missingRequiredScope(context)
    }
    val ignoredIds by produceState(initialValue = emptySet<String>(), refreshKey) {
        value = withContext(Dispatchers.IO) { ScopePromptStore.ignoredIds() }
    }
    val prompts = (missing ?: return)
        .sorted()
        .map { ScopePrompt(ScopePromptAction.RESTORE, it) }
        .filterNot { it.id in ignoredIds }
    if (prompts.isEmpty()) return

    val scope = rememberCoroutineScope()

    SmallTitle(text = stringResource(R.string.home_scope_title))
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            prompts.forEach { prompt ->
                key(prompt.id) {
                    BasicComponent(
                        title = friendlyProcessName(context, prompt.packageName),
                        summary = prompt.packageName,
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
                        when (val result = ScopeManager.request(prompts.map { it.packageName }.toSet())) {
                            is ScopeManager.Result.Failed ->
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            ScopeManager.Result.ServiceUnavailable ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.home_scope_service_unavailable),
                                    Toast.LENGTH_SHORT
                                ).show()
                            is ScopeManager.Result.Applied, ScopeManager.Result.NoChange -> refreshKey++
                            is ScopeManager.Result.Rejected -> Unit
                        }
                    }
                }
            )
            TextButton(
                text = stringResource(R.string.home_scope_ignore),
                onClick = {
                    ScopePromptStore.ignoreAll(prompts)
                    refreshKey++
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            )
        }
    }
}

/**
 * The launcher is not part of the static recommended scope. OS3 can still need it for the
 * Java predictive-back route, while OS4 can migrate an old installation away from the stale
 * launcher entry.
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun LauncherScopeSuggestionCard() {
    val context = LocalContext.current
    val service by XposedServiceManager.serviceFlow.collectAsState()
    var refreshKey by remember { mutableIntStateOf(0) }
    val recommendation by produceState<ScopePrompt?>(initialValue = null, service, refreshKey) {
        value = if (service == null) null else ScopeManager.launcherScopeRecommendation()
    }
    val ignoredIds by produceState(initialValue = emptySet<String>(), refreshKey) {
        value = withContext(Dispatchers.IO) { ScopePromptStore.ignoredIds() }
    }
    val prompt = recommendation ?: return
    if (prompt.id in ignoredIds) return

    val scope = rememberCoroutineScope()
    val restoring = prompt.action == ScopePromptAction.RESTORE

    SmallTitle(text = stringResource(R.string.home_scope_title))
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicComponent(
                title = friendlyProcessName(context, prompt.packageName),
                summary = prompt.packageName,
                startAction = {
                    Icon(
                        imageVector = if (restoring) Icons.Rounded.WarningAmber else Icons.Rounded.Info,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = stringResource(
                            if (restoring) R.string.home_scope_missing else R.string.home_scope_unneeded
                        ),
                        tint = if (restoring) Color(0xFFFFB300) else Color(0xFF42A5F5)
                    )
                }
            )
            ArrowPreference(
                title = stringResource(if (restoring) R.string.home_scope_restore else R.string.home_scope_remove),
                summary = stringResource(
                    if (restoring) R.string.home_scope_restore_summary else R.string.home_scope_remove_summary
                ),
                onClick = {
                    scope.launch {
                        when (val result = if (restoring) {
                            ScopeManager.request(setOf(prompt.packageName))
                        } else {
                            ScopeManager.remove(setOf(prompt.packageName))
                        }) {
                            is ScopeManager.Result.Failed ->
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            ScopeManager.Result.ServiceUnavailable ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.home_scope_service_unavailable),
                                    Toast.LENGTH_SHORT
                                ).show()
                            is ScopeManager.Result.Applied, ScopeManager.Result.NoChange -> refreshKey++
                            is ScopeManager.Result.Rejected -> Unit
                        }
                    }
                }
            )
            TextButton(
                text = stringResource(R.string.home_scope_ignore),
                onClick = {
                    ScopePromptStore.ignore(prompt)
                    refreshKey++
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            )
        }
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
        "com.xiaomi.bluetooth" -> context.getString(R.string.home_scope_bluetooth)
        "com.takekazex.hypertweak" -> "HyperTweak"
        else -> processName
    }
}
