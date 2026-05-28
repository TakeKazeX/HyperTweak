package com.takekazex.hypertweak.ui.page

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur

@Composable
fun HomeScreenContent(
    padding: PaddingValues,
    moduleActive: Boolean,
    packageName: String,
    targetSdk: Int,
    backdrop: LayerBackdrop
) {
    val isDark = isSystemInDarkTheme()
    val containerColor = if (moduleActive) {
        if (isDark) Color(0xFF1B5E20) else Color(0xFFE8F5E9)
    } else {
        if (isDark) Color(0xFFB71C1C) else Color(0xFFFFEBEE)
    }

    val contentColor = if (moduleActive) {
        if (isDark) Color(0xFFC8E6C9) else Color(0xFF1B5E20)
    } else {
        if (isDark) Color(0xFFFFCDD2) else Color(0xFFB71C1C)
    }

    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val surfaceColor = MiuixTheme.colorScheme.surface
    val topBarBackdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = "Ink Tweaks",
                modifier = Modifier.textureBlur(
                    backdrop = topBarBackdrop,
                    shape = RectangleShape,
                    blurRadius = 25f,
                    colors = BlurDefaults.blurColors(blendColors = listOf(
                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f))
                    ))
                ),
                color = Color.Transparent,
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .layerBackdrop(topBarBackdrop)
                .overScrollVertical()
                .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Spacer(modifier = Modifier.height(innerPadding.calculateTopPadding()))
            Spacer(modifier = Modifier.height(8.dp))

            // Large Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(containerColor)
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .offset(x = 24.dp, y = 24.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Icon(
                            imageVector = if (moduleActive) MiuixIcons.Basic.Check else MiuixIcons.Info,
                            tint = contentColor.copy(alpha = 0.12f),
                            modifier = Modifier.size(120.dp),
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
                            color = contentColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (moduleActive)
                                "Native libxposed module loaded successfully."
                            else
                                "Please enable the module in LSPosed manager, ensure 'Ink Tweaks' itself is checked in the scope, and reboot or restart SystemUI.",
                            color = contentColor.copy(alpha = 0.8f),
                            fontSize = 13.sp
                        )
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

            Spacer(modifier = Modifier.height(padding.calculateBottomPadding() + 16.dp))
        }
    }
}
