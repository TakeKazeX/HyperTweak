package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
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
    val scrollState = rememberScrollState()
    val scrollProgress = remember(scrollState.value) {
        (scrollState.value.toFloat() / 600f).coerceIn(0f, 1f)
    }

    val localBackdrop = rememberLayerBackdrop {
        drawContent()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            BgEffectBackground(
                dynamicBackground = true,
                modifier = Modifier.fillMaxSize(),
                bgModifier = Modifier.layerBackdrop(localBackdrop),
                alpha = { 1f - scrollProgress },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    // Push content below back button safe area
                    Spacer(modifier = Modifier.height(72.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // App icon
                        Icon(
                            imageVector = MiuixIcons.Favorites,
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(80.dp),
                            contentDescription = null
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Large app name title (like InstallerX title2 style)
                        Text(
                            text = "Ink Tweaks",
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Version subtitle with reduced opacity
                        Text(
                            text = "Version 1.0 (1)",
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // SmallTitle uses PaddingValues(28.dp, 8.dp) so it has proper left indent
                    SmallTitle(text = "PROJECT")

                    // Card with View Source Code & Credits links
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

                    Spacer(modifier = Modifier.height(48.dp))
                }
            }

            // Floating Top-Left Back Button with Status Bar Padding
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.TopStart
            ) {
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
        }
    }
}
