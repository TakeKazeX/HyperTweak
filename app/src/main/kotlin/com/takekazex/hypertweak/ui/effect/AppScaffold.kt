package com.takekazex.hypertweak.ui.effect

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost

private val LocalTopBarBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
private fun Modifier.topBarBlur(enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val backdrop = LocalTopBarBackdrop.current ?: return this
    return appTextureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        colors = BlurDefaults.blurColors(blendColors = listOf(
            BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = 0.8f)),
        )),
    )
}

@Composable
fun AppScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable (() -> Unit)? = null,
    topBarBackdrop: LayerBackdrop? = null,
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    floatingToolbar: @Composable () -> Unit = {},
    floatingToolbarPosition: ToolbarPosition = ToolbarPosition.BottomCenter,
    snackbarHost: @Composable () -> Unit = {},
    popupHost: @Composable () -> Unit = { MiuixPopupHost() },
    containerColor: Color = MiuixTheme.colorScheme.surface,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    content: @Composable (PaddingValues) -> Unit,
) {
    val ready = rememberContentReady()
    val active = ready && topBar != null && LocalAppBlurMode.current != AppBlurMode.Off
    val capturedBackdrop = rememberLayerBackdrop { drawRect(containerColor); drawContent() }
    val backdrop = topBarBackdrop ?: capturedBackdrop
    CompositionLocalProvider(LocalTopBarBackdrop provides if (active) backdrop else null) {
        top.yukonga.miuix.kmp.basic.Scaffold(
            modifier = modifier,
            topBar = topBar ?: {},
            bottomBar = bottomBar,
            floatingActionButton = floatingActionButton,
            floatingActionButtonPosition = floatingActionButtonPosition,
            floatingToolbar = floatingToolbar,
            floatingToolbarPosition = floatingToolbarPosition,
            snackbarHost = snackbarHost,
            popupHost = popupHost,
            containerColor = containerColor,
            contentWindowInsets = contentWindowInsets,
            content = { padding ->
                Box(Modifier.fillMaxSize().then(if (active && topBarBackdrop == null) Modifier.layerBackdrop(capturedBackdrop) else Modifier)) {
                    content(padding)
                }
            },
        )
    }
}

@Composable
fun AppTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    largeTitle: String = title,
    largeTitleColor: Color = MiuixTheme.colorScheme.onSurface,
    subtitle: String = "",
    subtitleColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    defaultWindowInsetsPadding: Boolean = true,
    titlePadding: Dp = TopAppBarDefaults.TitlePadding,
    navigationIconPadding: Dp = TopAppBarDefaults.NavigationIconPadding,
    actionIconPadding: Dp = TopAppBarDefaults.ActionIconPadding,
    bottomContent: @Composable () -> Unit = {},
) {
    val blurred = LocalTopBarBackdrop.current != null
    top.yukonga.miuix.kmp.basic.TopAppBar(
        title = title,
        modifier = modifier.topBarBlur(),
        color = if (blurred) Color.Transparent else color,
        titleColor = titleColor,
        largeTitle = largeTitle,
        largeTitleColor = largeTitleColor,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        titlePadding = titlePadding,
        navigationIconPadding = navigationIconPadding,
        actionIconPadding = actionIconPadding,
        bottomContent = bottomContent,
    )
}

@Composable
fun AppSmallTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    blurEnabled: Boolean = true,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    subtitle: String = "",
    subtitleColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    defaultWindowInsetsPadding: Boolean = true,
    titlePadding: Dp = TopAppBarDefaults.TitlePadding,
    navigationIconPadding: Dp = TopAppBarDefaults.NavigationIconPadding,
    actionIconPadding: Dp = TopAppBarDefaults.ActionIconPadding,
    bottomContent: @Composable () -> Unit = {},
) {
    val blurred = blurEnabled && LocalTopBarBackdrop.current != null
    top.yukonga.miuix.kmp.basic.SmallTopAppBar(
        title = title,
        modifier = modifier.topBarBlur(blurEnabled),
        color = if (blurred) Color.Transparent else color,
        titleColor = titleColor,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        titlePadding = titlePadding,
        navigationIconPadding = navigationIconPadding,
        actionIconPadding = actionIconPadding,
        bottomContent = bottomContent,
    )
}
