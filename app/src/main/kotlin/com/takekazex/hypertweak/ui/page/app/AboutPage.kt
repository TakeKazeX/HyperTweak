// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(ExperimentalScrollBarApi::class)

package com.takekazex.hypertweak.ui.page.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.computedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takekazex.hypertweak.BuildConfig
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppPage
import com.takekazex.hypertweak.ui.chrome.appPageScroll
import com.takekazex.hypertweak.ui.chrome.rememberPageBackdrop
import com.takekazex.hypertweak.ui.effect.BgEffectBackground
import com.takekazex.hypertweak.ui.page.update.updateStatusSummary
import com.takekazex.hypertweak.util.update.UpdateManager
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val AboutContentWidth = 600.dp

private fun Color.isLight(): Boolean {
    val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return luminance > 0.5f
}

@Composable
private fun ValueText(text: String) {
    Text(
        text = text,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}

@Composable
fun AboutPage(
    onBack: () -> Unit,
    onViewSourceCode: () -> Unit,
    onJoinTelegramGroup: () -> Unit,
    onNavigateToCredits: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onNavigateToUpdate: () -> Unit,
    updateManager: UpdateManager,
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    var logoHeightPx by remember { mutableIntStateOf(0) }

    val scrollProgress by remember {
        computedStateOf {
            if (logoHeightPx <= 0) {
                0f
            } else {
                val index = lazyListState.firstVisibleItemIndex
                val offset = lazyListState.firstVisibleItemScrollOffset
                if (index > 0) 1f else (offset.toFloat() / logoHeightPx).coerceIn(0f, 1f)
            }
        }
    }

    // Upstream About: the title fades in once the logo is half collapsed; the bar's blur and the
    // glass band appear once the logo has fully scrolled away.
    val collapsed by remember { computedStateOf { scrollProgress == 1f } }
    val showTitle by remember { computedStateOf { scrollProgress >= 0.45f } }
    val titleAlpha = animateFloatAsState(
        targetValue = if (showTitle) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "aboutTitleAlpha",
    )
    AppPage(
        title = stringResource(R.string.about_title),
        onBack = onBack,
        backContentDescription = stringResource(R.string.about_back),
        expandable = false,
        scrollBehavior = topAppBarScrollBehavior,
        contentScrolled = collapsed,
        titleAlpha = { titleAlpha.value },
    ) { innerPadding ->
        AboutContentBody(
            innerPadding = innerPadding,
            topAppBarScrollBehavior = topAppBarScrollBehavior,
            lazyListState = lazyListState,
            scrollProgressProvider = { scrollProgress },
            onLogoHeightChanged = { logoHeightPx = it },
            onViewSourceCode = onViewSourceCode,
            onJoinTelegramGroup = onJoinTelegramGroup,
            onNavigateToCredits = onNavigateToCredits,
            onNavigateToDebug = onNavigateToDebug,
            onNavigateToUpdate = onNavigateToUpdate,
            updateManager = updateManager,
        )
    }
}

@Composable
private fun AboutContentBody(
    innerPadding: PaddingValues,
    topAppBarScrollBehavior: ScrollBehavior,
    lazyListState: LazyListState,
    scrollProgressProvider: () -> Float,
    onLogoHeightChanged: (Int) -> Unit,
    onViewSourceCode: () -> Unit,
    onJoinTelegramGroup: () -> Unit,
    onNavigateToCredits: () -> Unit,
    onNavigateToDebug: () -> Unit,
    onNavigateToUpdate: () -> Unit,
    updateManager: UpdateManager,
) {
    val layoutDirection = LocalLayoutDirection.current
    val uriHandler = LocalUriHandler.current
    val contentBackdrop = rememberPageBackdrop()

    val isDarkTheme = !MiuixTheme.colorScheme.background.isLight()
    val logoBlend = remember(isDarkTheme) {
        if (isDarkTheme) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }

    val density = LocalDensity.current
    var logoHeightDp by remember { mutableStateOf(300.dp) }

    val displayCutoutInsets = WindowInsets.displayCutout.asPaddingValues()
    val horizontalSafeInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()

    val listContentPadding = PaddingValues(
        start = horizontalSafeInsets.calculateStartPadding(layoutDirection) + displayCutoutInsets.calculateLeftPadding(layoutDirection),
        top = innerPadding.calculateTopPadding(),
        end = horizontalSafeInsets.calculateEndPadding(layoutDirection) + displayCutoutInsets.calculateRightPadding(layoutDirection),
        bottom = innerPadding.calculateBottomPadding(),
    )

    val logoPadding = PaddingValues(
        top = innerPadding.calculateTopPadding() + 40.dp,
        start = horizontalSafeInsets.calculateStartPadding(layoutDirection) + displayCutoutInsets.calculateLeftPadding(layoutDirection),
        end = horizontalSafeInsets.calculateEndPadding(layoutDirection) + displayCutoutInsets.calculateRightPadding(layoutDirection),
    )

    val updateState by updateManager.state.collectAsStateWithLifecycle()
    val lastCheckedAt = remember(updateManager, updateState) { updateManager.lastCheckedAt() }

    BgEffectBackground(
        dynamicBackground = true,
        isOs3Effect = true,
        isFullSize = false,
        modifier = Modifier.fillMaxSize(),
        bgModifier = contentBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier,
        alpha = { 1f - scrollProgressProvider() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = logoPadding.calculateTopPadding() + 52.dp,
                    start = logoPadding.calculateStartPadding(layoutDirection),
                    end = logoPadding.calculateEndPadding(layoutDirection),
                )
                .onSizeChanged { size ->
                    with(density) { logoHeightDp = size.height.toDp() }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(88.dp)
                    .graphicsLayer {
                        val iconProgress = ((scrollProgressProvider() - 0.35f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - iconProgress
                        scaleX = 1 - (iconProgress * 0.05f)
                        scaleY = 1 - (iconProgress * 0.05f)
                    },
            ) {
                if (contentBackdrop != null) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        modifier = Modifier
                            .requiredSize(150.dp)
                            .textureBlur(
                                backdrop = contentBackdrop,
                                shape = RoundedCornerShape(16.dp),
                                blurRadius = 200f,
                                noiseCoefficient = BlurDefaults.NoiseCoefficient,
                                colors = BlurDefaults.blurColors(blendColors = logoBlend),
                                contentBlendMode = ComposeBlendMode.DstIn,
                                enabled = true,
                            ),
                        contentDescription = null,
                    )
                } else {
                    val context = LocalContext.current
                    val icon = remember(context) {
                        context.applicationInfo.loadIcon(context.packageManager).toBitmap(192, 192).asImageBitmap()
                    }
                    Image(
                        bitmap = icon,
                        modifier = Modifier.size(80.dp),
                        contentDescription = stringResource(id = R.string.about_title),
                    )
                }
            }

            Text(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 5.dp)
                    .graphicsLayer {
                        val projectNameProgress = ((scrollProgressProvider() - 0.20f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - projectNameProgress
                        scaleX = 1 - (projectNameProgress * 0.05f)
                        scaleY = 1 - (projectNameProgress * 0.05f)
                    }
                    .then(
                        if (contentBackdrop != null) {
                            Modifier.textureBlur(
                                backdrop = contentBackdrop,
                                shape = RoundedCornerShape(16.dp),
                                blurRadius = 150f,
                                noiseCoefficient = BlurDefaults.NoiseCoefficient,
                                colors = BlurDefaults.blurColors(blendColors = logoBlend),
                                contentBlendMode = ComposeBlendMode.DstIn,
                                enabled = true,
                            )
                        } else {
                            Modifier
                        }
                    ),
                text = "HyperTweak",
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
            )

            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val versionCodeProgress = ((scrollProgressProvider() - 0.05f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - versionCodeProgress
                        scaleX = 1 - (versionCodeProgress * 0.05f)
                        scaleY = 1 - (versionCodeProgress * 0.05f)
                    },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MiuixTheme.textStyles.body2,
                textAlign = TextAlign.Center,
            )
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .appPageScroll(topAppBarScrollBehavior),
            contentPadding = listContentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            overscrollEffect = null,
        ) {
            item(key = "logoSpacer") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            logoHeightDp + 52.dp + logoPadding.calculateTopPadding() - listContentPadding.calculateTopPadding() + 126.dp,
                        )
                        .onSizeChanged { size ->
                            onLogoHeightChanged(size.height)
                        },
                    contentAlignment = Alignment.TopCenter,
                    content = {},
                )
            }

            item(key = "about_content") {
                val section = Modifier
                    .widthIn(max = AboutContentWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)

                Column(
                    modifier = Modifier
                        .fillParentMaxHeight()
                        .padding(bottom = listContentPadding.calculateBottomPadding() + 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GlassCard(modifier = section) {
                        ArrowPreference(
                            title = stringResource(R.string.update_title),
                            summary = updateStatusSummary(updateState, lastCheckedAt),
                            onClick = onNavigateToUpdate,
                        )
                    }

                    SmallTitle(
                        text = stringResource(R.string.about_project),
                        modifier = Modifier
                            .widthIn(max = AboutContentWidth)
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )

                    GlassCard(modifier = section) {
                        ArrowPreference(
                            title = stringResource(R.string.about_view_source_code),
                            summary = stringResource(R.string.about_view_source_code_summary),
                            endActions = { ValueText("GitHub") },
                            onClick = onViewSourceCode,
                        )
                        ArrowPreference(
                            title = stringResource(R.string.about_telegram_group),
                            summary = stringResource(R.string.about_telegram_group_summary),
                            endActions = { ValueText("Telegram") },
                            onClick = onJoinTelegramGroup,
                        )
                        ArrowPreference(
                            title = stringResource(R.string.about_credits_acknowledgements),
                            summary = stringResource(R.string.about_credits_acknowledgements_summary),
                            onClick = onNavigateToCredits,
                        )
                    }

                    SmallTitle(
                        text = stringResource(R.string.settings_debug),
                        modifier = Modifier
                            .widthIn(max = AboutContentWidth)
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )

                    GlassCard(modifier = section) {
                        ArrowPreference(
                            title = stringResource(R.string.settings_debug),
                            summary = stringResource(R.string.settings_debug_summary),
                            onClick = onNavigateToDebug,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(lazyListState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = listContentPadding,
        )
    }
}
