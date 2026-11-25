package com.takekazex.hypertweak.ui.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.computedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.ui.effect.AppBlurMode
import com.takekazex.hypertweak.ui.effect.LocalAppBlurMode
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Whether content sits under the OS4 top bar. Tab rows in the bar's bottom content read it to
 * raise their resting glass track (upstream `surfaceAlpha`).
 */
internal val LocalTopBarContentScrolled = staticCompositionLocalOf { false }

/**
 * The app's only top bar (upstream example `AdaptiveTopAppBar` + `BlurredBar`).
 *
 * OS4: upstream [GlassTopAppBar]; the band stays clear until content scrolls under it, then fades
 * in as a progressive blur of [LocalTopAppBarBackdrop] (a surface gradient when blur is off); slot
 * buttons become glass pills. OS3: Miuix [TopAppBar] over a blurred layer of the same backdrop.
 *
 * @param contentScrolled Explicit overlap state for pages whose header is not a scroll offset
 *   (About); null derives it from [scrollBehavior]. Upward overscroll always counts as scrolled.
 * @param titleAlpha Extra title opacity (About's collapsing logo).
 */
@Composable
fun AppTopBar(
    title: String,
    scrollBehavior: ScrollBehavior?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = "",
    subtitle: String = "",
    expandable: Boolean = true,
    contentScrolled: Boolean? = null,
    titleAlpha: (() -> Float)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    val backdrop = LocalTopAppBarBackdrop.current
    val navigationIcon: @Composable () -> Unit = {
        if (onBack != null) BackNavigationIcon(onClick = onBack, contentDescription = backContentDescription)
    }
    if (LocalAppUiStyle.current == AppUiStyle.Os4) {
        val chromeTitleAlpha = LocalTopBarTitleAlpha.current
        val surface = MiuixTheme.colorScheme.surface
        val bandBrush = remember(surface) { GlassTopAppBarDefaults.bandBrush(surface) }
        // Upstream thresholds: -1px absorbs rebound jitter at the edge, and upward overscroll on
        // a short page still floats the bar material.
        val scrolled by remember(scrollBehavior, contentScrolled) {
            computedStateOf {
                val overscrolled = scrollBehavior?.state?.let { it.overscrollOffset < -1f } ?: false
                (contentScrolled ?: (scrollBehavior?.state?.let { it.contentOffset < -1f } ?: false)) || overscrolled
            }
        }
        GlassTopAppBar(
            title = title,
            isContentScrolled = scrolled,
            modifier = modifier,
            contentModifier = Modifier.sidebarStartInset(),
            expandable = expandable,
            progressiveBlur = backdrop != null,
            backdrop = backdrop,
            subtitle = subtitle,
            scrollBehavior = scrollBehavior,
            bandBrush = bandBrush,
            bandOverhang = GlassTopAppBarDefaults.BandOverhang,
            titleAlpha = { (titleAlpha?.invoke() ?: 1f) * chromeTitleAlpha() },
            navigationIcon = { GlassTopBarSlot(navigationIcon) },
            actions = {
                GlassTopBarSlot {
                    // Upstream spaces adjacent glass pills by 8dp; pills carry no padding.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            },
            bottomContent = {
                CompositionLocalProvider(LocalTopBarContentScrolled provides scrolled, content = bottomContent)
            },
        )
        return
    }
    val blurred = backdrop != null && contentScrolled != false
    val surface = MiuixTheme.colorScheme.surface
    val titleColor = MiuixTheme.colorScheme.onSurface.let { c ->
        if (titleAlpha != null) c.copy(alpha = titleAlpha().coerceIn(0f, 1f)) else c
    }
    Box(modifier) {
        if (blurred) {
            Box(Modifier.matchParentSize().barBlur(backdrop))
        }
        TopAppBar(
            title = title,
            expandable = expandable,
            color = when {
                blurred -> Color.Transparent
                contentScrolled == false -> Color.Transparent
                else -> surface
            },
            titleColor = titleColor,
            subtitle = subtitle,
            navigationIcon = navigationIcon,
            actions = actions,
            scrollBehavior = scrollBehavior,
            bottomContent = bottomContent,
        )
    }
}

@Composable
private fun Modifier.barBlur(backdrop: LayerBackdrop): Modifier {
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = 0.8f))),
    )
    return if (LocalAppBlurMode.current == AppBlurMode.Progressive) {
        progressiveTextureBlur(backdrop, RectangleShape, 25f, gradient = ProgressiveBlur.Top, colors = colors)
    } else {
        textureBlur(backdrop, RectangleShape, 25f, colors = colors)
    }
}
