package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.takekazex.hypertweak.ui.effect.BgEffectBackground

@Composable
fun AboutPage(
    onBack: () -> Unit,
    onViewSourceCode: () -> Unit,
    onNavigateToCredits: () -> Unit
) {
    val lazyListState = rememberLazyListState()
    val scrollProgress by remember {
        derivedStateOf {
            val index = lazyListState.firstVisibleItemIndex
            val offset = lazyListState.firstVisibleItemScrollOffset
            if (index > 0) 1f else (offset.toFloat() / 400f).coerceIn(0f, 1f)
        }
    }

    val localBackdrop = rememberLayerBackdrop {
        drawContent()
    }

    val topAppBarScrollBehavior = MiuixScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            SmallTopAppBar(
                title = "About",
                scrollBehavior = topAppBarScrollBehavior,
                color = MiuixTheme.colorScheme.surface.copy(alpha = scrollProgress),
                titleColor = MiuixTheme.colorScheme.onSurface.copy(alpha = scrollProgress),
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            tint = MiuixTheme.colorScheme.onSurface,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            BgEffectBackground(
                dynamicBackground = true,
                modifier = Modifier.fillMaxSize(),
                bgModifier = Modifier.layerBackdrop(localBackdrop),
                alpha = { 1f - scrollProgress },
            ) {
                // Fixed/Parallax Logo Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 56.dp)
                        .graphicsLayer {
                            alpha = 1f - scrollProgress
                            val scale = 1f - (scrollProgress * 0.08f)
                            scaleX = scale
                            scaleY = scale
                            translationY = -scrollProgress * 120f
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // App icon with premium texture blur background
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = MiuixIcons.Favorites,
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .size(88.dp)
                                .textureBlur(
                                    backdrop = localBackdrop,
                                    shape = RoundedCornerShape(18.dp),
                                    blurRadius = 150f,
                                    colors = BlurDefaults.blurColors(
                                        blendColors = listOf(
                                            BlendColorEntry(color = MiuixTheme.colorScheme.primary.copy(0.2f))
                                        )
                                    ),
                                    enabled = true
                                ),
                            contentDescription = null
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Large app title with glassmorphism texture blur
                    Text(
                        modifier = Modifier.textureBlur(
                            backdrop = localBackdrop,
                            shape = RoundedCornerShape(8.dp),
                            blurRadius = 100f,
                            colors = BlurDefaults.blurColors(
                                blendColors = listOf(
                                    BlendColorEntry(color = MiuixTheme.colorScheme.onSurface.copy(0.05f))
                                )
                            ),
                            enabled = true
                        ),
                        text = "Ink Tweaks",
                        fontWeight = FontWeight.Bold,
                        fontSize = 35.sp,
                        color = MiuixTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Version subtitle
                    Text(
                        text = "Version 1.0 (1)",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp
                    )
                }

                // Scrollable content on top
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        bottom = innerPadding.calculateBottomPadding() + 48.dp
                    )
                ) {
                    item {
                        // Spacer overlay matching the height of the parallax logo header
                        Spacer(modifier = Modifier.height(280.dp))
                    }

                    item {
                        SmallTitle(text = "PROJECT")
                    }

                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .textureBlur(
                                    backdrop = localBackdrop,
                                    shape = RoundedCornerShape(16.dp),
                                    blurRadius = 25f,
                                    colors = BlurDefaults.blurColors(
                                        blendColors = listOf(
                                            BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.3f)),
                                        ),
                                    )
                                ),
                            colors = CardDefaults.defaultColors(Color.Transparent, Color.Transparent)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                ArrowPreference(
                                    title = "View Source Code",
                                    summary = "Check the GitHub repository",
                                    onClick = onViewSourceCode
                                )
                                ArrowPreference(
                                    title = "Credits & Acknowledgements",
                                    summary = "View open source libraries and contributors",
                                    onClick = onNavigateToCredits
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
