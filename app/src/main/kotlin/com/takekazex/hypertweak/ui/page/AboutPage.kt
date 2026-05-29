package com.takekazex.hypertweak.ui.page

import com.takekazex.hypertweak.BuildConfig
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.onEach
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
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import com.takekazex.hypertweak.ui.effect.rememberContentReady
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.takekazex.hypertweak.ui.effect.BgEffectBackground
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode

@Composable
fun AboutPage(
    onBack: () -> Unit,
    onViewSourceCode: () -> Unit,
    onNavigateToCredits: () -> Unit
) {
    val lazyListState = rememberLazyListState()
    var logoHeightPx by remember { mutableIntStateOf(0) }

    val scrollProgress by remember {
        derivedStateOf {
            if (logoHeightPx <= 0) {
                0f
            } else {
                val index = lazyListState.firstVisibleItemIndex
                val offset = lazyListState.firstVisibleItemScrollOffset
                if (index > 0) 1f else (offset.toFloat() / logoHeightPx).coerceIn(0f, 1f)
            }
        }
    }

    val surfaceColor = MiuixTheme.colorScheme.surface
    val localBackdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
    val contentReady = rememberContentReady()

    val topAppBarScrollBehavior = MiuixScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            AboutTopBar(
                scrollProgressProvider = { scrollProgress },
                onBack = onBack,
                topAppBarScrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val density = LocalDensity.current

        val isInDark = isSystemInDarkTheme()
        val primaryColor = MiuixTheme.colorScheme.primary
        val logoBlend = remember(isInDark, primaryColor) {
            if (isInDark) {
                listOf(
                    BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                    BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                    BlendColorEntry(primaryColor, BlurBlendMode.Lab),
                )
            } else {
                listOf(
                    BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                    BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                    BlendColorEntry(primaryColor, BlurBlendMode.Lab),
                )
            }
        }

        val containerColor = MiuixTheme.colorScheme.surfaceContainer
        val cardBlendColors = remember(isInDark, containerColor) {
            if (isInDark) {
                listOf(BlendColorEntry(containerColor.copy(0.4f)))
            } else {
                listOf(BlendColorEntry(containerColor.copy(0.3f)))
            }
        }

        // Logo parallax/fade tracking
        var logoHeightDp by remember { mutableStateOf(300.dp) }
        var logoAreaY by remember { mutableFloatStateOf(0f) }
        var iconY by remember { mutableFloatStateOf(0f) }
        var projectNameY by remember { mutableFloatStateOf(0f) }
        var versionCodeY by remember { mutableFloatStateOf(0f) }

        var iconProgress by remember { mutableFloatStateOf(0f) }
        var projectNameProgress by remember { mutableFloatStateOf(0f) }
        var versionCodeProgress by remember { mutableFloatStateOf(0f) }
        var initialLogoAreaY by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(lazyListState) {
            snapshotFlow { lazyListState.firstVisibleItemScrollOffset }
                .onEach { offset ->
                    if (lazyListState.firstVisibleItemIndex > 0) {
                        if (iconProgress != 1f) iconProgress = 1f
                        if (projectNameProgress != 1f) projectNameProgress = 1f
                        if (versionCodeProgress != 1f) versionCodeProgress = 1f
                        return@onEach
                    }

                    if (initialLogoAreaY == 0f && logoAreaY > 0f) {
                        initialLogoAreaY = logoAreaY
                    }
                    val refLogoAreaY = if (initialLogoAreaY > 0f) initialLogoAreaY else logoAreaY

                    val stage1TotalLength = refLogoAreaY - versionCodeY
                    val stage2TotalLength = versionCodeY - projectNameY
                    val stage3TotalLength = projectNameY - iconY

                    val versionCodeDelay = stage1TotalLength * 0.5f
                    versionCodeProgress = ((offset.toFloat() - versionCodeDelay) / (stage1TotalLength - versionCodeDelay).coerceAtLeast(1f))
                        .coerceIn(0f, 1f)
                    projectNameProgress = ((offset.toFloat() - stage1TotalLength) / stage2TotalLength.coerceAtLeast(1f))
                        .coerceIn(0f, 1f)
                    iconProgress = ((offset.toFloat() - stage1TotalLength - stage2TotalLength) / stage3TotalLength.coerceAtLeast(1f))
                        .coerceIn(0f, 1f)
                }
                .collect { }
        }

        val scrollPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            start = innerPadding.calculateStartPadding(layoutDirection),
            end = innerPadding.calculateEndPadding(layoutDirection),
        )
        val logoPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() + 40.dp,
            start = innerPadding.calculateStartPadding(layoutDirection),
            end = innerPadding.calculateEndPadding(layoutDirection),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            BgEffectBackground(
                dynamicBackground = true,
                modifier = Modifier.fillMaxSize(),
                bgModifier = Modifier.layerBackdrop(localBackdrop),
                alpha = { 1f - scrollProgress },
            ) {
                // Logo area
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
                            .size(100.dp)
                            .clip(CircleShape)
                            .graphicsLayer {
                                alpha = 1f - iconProgress
                                scaleX = 1f - (iconProgress * 0.05f)
                                scaleY = 1f - (iconProgress * 0.05f)
                            }
                            .onGloballyPositioned { coordinates ->
                                if (iconY != 0f) return@onGloballyPositioned
                                val y = coordinates.positionInWindow().y
                                val size = coordinates.size
                                iconY = y + size.height
                            },
                    ) {
                        Image(
                            modifier = Modifier
                                .requiredSize(245.dp)
                                .then(Modifier.textureBlur(
                                    backdrop = localBackdrop,
                                    shape = RoundedCornerShape(0.dp),
                                    blurRadius = 0f,
                                    colors = BlurColors(blendColors = logoBlend),
                                    contentBlendMode = ComposeBlendMode.DstIn,
                                    enabled = true,
                                )),
                            painter = painterResource(id = com.takekazex.hypertweak.R.drawable.ic_launcher_foreground),
                            colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground),
                            contentDescription = null,
                        )
                    }
                    Text(
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 5.dp)
                            .onGloballyPositioned { coordinates ->
                                if (projectNameY != 0f) return@onGloballyPositioned
                                val y = coordinates.positionInWindow().y
                                val size = coordinates.size
                                projectNameY = y + size.height
                            }
                            .graphicsLayer {
                                alpha = 1f - projectNameProgress
                                scaleX = 1f - (projectNameProgress * 0.05f)
                                scaleY = 1f - (projectNameProgress * 0.05f)
                            }
                            .then(Modifier.textureBlur(
                                backdrop = localBackdrop,
                                shape = RoundedCornerShape(0.dp),
                                blurRadius = 0f,
                                colors = BlurColors(blendColors = logoBlend),
                                contentBlendMode = ComposeBlendMode.DstIn,
                                enabled = true,
                            )),
                        text = "HyperTweak",
                        color = MiuixTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 35.sp,
                    )
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = 1f - versionCodeProgress
                                scaleX = 1f - (versionCodeProgress * 0.05f)
                                scaleY = 1f - (versionCodeProgress * 0.05f)
                            }
                            .onGloballyPositioned { coordinates ->
                                if (versionCodeY != 0f) return@onGloballyPositioned
                                val y = coordinates.positionInWindow().y
                                val size = coordinates.size
                                versionCodeY = y + size.height
                            },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        text = "Version v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                // Scrollable content
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        top = scrollPadding.calculateTopPadding(),
                        start = scrollPadding.calculateStartPadding(layoutDirection),
                        end = scrollPadding.calculateEndPadding(layoutDirection),
                        bottom = scrollPadding.calculateBottomPadding() + 48.dp
                    )
                ) {
                    item(key = "logoSpacer") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(
                                    logoHeightDp + 52.dp + logoPadding.calculateTopPadding() - scrollPadding.calculateTopPadding() + 126.dp,
                                )
                                .onSizeChanged { size ->
                                    logoHeightPx = size.height
                                }
                                .onGloballyPositioned { coordinates ->
                                    val y = coordinates.positionInWindow().y
                                    val size = coordinates.size
                                    logoAreaY = y + size.height
                                },
                            contentAlignment = Alignment.TopCenter,
                            content = { },
                        )
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
                                    colors = BlurColors(blendColors = cardBlendColors),
                                    enabled = true
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

@Composable
private fun AboutTopBar(
    scrollProgressProvider: () -> Float,
    onBack: () -> Unit,
    topAppBarScrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior
) {
    val scrollProgress = scrollProgressProvider()
    val layoutDirection = LocalLayoutDirection.current
    SmallTopAppBar(
        title = "About",
        scrollBehavior = topAppBarScrollBehavior,
        color = MiuixTheme.colorScheme.surface.copy(alpha = if (scrollProgress == 1f) 1f else 0f),
        titleColor = MiuixTheme.colorScheme.onSurface.copy(
            alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f)
        ),
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    modifier = Modifier.graphicsLayer {
                        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                    },
                    imageVector = MiuixIcons.Back,
                    tint = MiuixTheme.colorScheme.onSurface,
                    contentDescription = "Back"
                )
            }
        }
    )
}
