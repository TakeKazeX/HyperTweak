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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
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
    packageName: String,
    targetSdk: Int,
    backdrop: LayerBackdrop,
    onNavigateToHiddenFeatures: () -> Unit,
    onRestartScope: (systemUi: Boolean, settings: Boolean, aod: Boolean, securityCenter: Boolean, scanner: Boolean, milink: Boolean, bluetooth: Boolean) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val containerColor = if (moduleActive) {
        if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    } else {
        if (isDark) Color(0xFF381A1A) else Color(0xFFFAEEEE)
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
                )
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
                            imageVector = if (moduleActive) Icons.Rounded.CheckCircleOutline else Icons.Rounded.ErrorOutline,
                            tint = if (moduleActive) Color(0xFF36D167) else Color(0xFFD13636),
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
                            text = if (moduleActive) "Module is ACTIVE" else "Module is NOT ACTIVE",
                            color = textContentColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (moduleActive)
                                "Native libxposed module loaded successfully."
                            else
                                "Please enable the module in LSPosed manager, ensure 'HyperTweak' itself is checked in the scope, and reboot or restart SystemUI.",
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
    }
}
