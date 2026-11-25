package com.takekazex.hypertweak.ui.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.takekazex.hypertweak.ui.effect.AppBlurMode
import com.takekazex.hypertweak.ui.effect.LocalAppBlurMode
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * App chrome generation. [Os3] keeps the classic Miuix bars; [Os4] uses the upstream
 * `miuix-glass` components. Persisted IDs follow declaration order (`KEY_APP_UI_STYLE`).
 */
enum class AppUiStyle { Os3, Os4 }

val LocalAppUiStyle = staticCompositionLocalOf { AppUiStyle.Os4 }

fun appUiStyleFromStored(value: Int): AppUiStyle = AppUiStyle.entries.getOrElse(value) { AppUiStyle.Os4 }

/**
 * Whole-page recording (top bar, content and bottom bar), provided by [PageBackdropBox]. Floating
 * overlays sample it: glass dialogs, bottom sheets and dropdown popups. Null means blur is off.
 */
internal val LocalPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * Content-only recording behind the top bar, provided by [AppPage]. The glass top bar, its
 * buttons and its tab row sample it; it never contains the bar itself.
 */
internal val LocalTopAppBarBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Draw-phase title opacity for chrome that covers the top bar title (the glass rail). */
val LocalTopBarTitleAlpha = staticCompositionLocalOf<() -> Float> { { 1f } }

/**
 * Start space taken by the expanded glass rail. The page backdrop stays full screen; the top bar
 * and page content apply it at layout time ([sidebarStartInset]).
 */
val LocalTopBarStartInset = staticCompositionLocalOf<() -> Dp> { { 0.dp } }

/**
 * Top clearance for the glass search bar below the collapsed rail capsule, which sits over the
 * top bar center (upstream `searchTop`). Zero without a rail.
 */
val LocalSearchTopInset = staticCompositionLocalOf<() -> Dp> { { 0.dp } }

/**
 * Search mode of the host page, for the OS4 top bar's upstream `glassSearchBarTransition` (fade,
 * shrink and blur while the glass search overlay is open). Null where the page has no overlay.
 */
val LocalTopBarSearchExpanded = staticCompositionLocalOf<Boolean?> { null }

/** Set inside [AppPage]; overlay dialogs and dropdowns need its popup host. */
internal val LocalAppOverlayHost = staticCompositionLocalOf { false }

/** Upstream `rememberBlurBackdrop`: a page recording, or null when blur is off or unsupported. */
@Composable
fun rememberPageBackdrop(): LayerBackdrop? {
    if (LocalAppBlurMode.current == AppBlurMode.Off || !isRuntimeShaderSupported()) return null
    val surface = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
}

/**
 * Upstream `PageBackdropBox`: records everything inside it into [LocalPageBackdrop] for floating
 * overlays. The outermost box wins; nested ones only lay out their content. [enabled] = false
 * keeps the same tree without recording, so toggling it never recreates [content].
 */
@Composable
fun PageBackdropBox(modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    if (LocalPageBackdrop.current != null) {
        Box(modifier) { content() }
        return
    }
    val pageBackdrop = if (enabled) rememberPageBackdrop() else null
    CompositionLocalProvider(LocalPageBackdrop provides pageBackdrop) {
        Box(modifier.then(if (pageBackdrop != null) Modifier.layerBackdrop(pageBackdrop) else Modifier)) {
            content()
        }
    }
}

/** Moves content clear of the expanded glass rail ([LocalTopBarStartInset]) at layout time. */
@Composable
fun Modifier.sidebarStartInset(): Modifier {
    val startInset = LocalTopBarStartInset.current
    return layout { measurable, constraints ->
        val insetPx = startInset().roundToPx().coerceIn(0, constraints.maxWidth)
        val placeable = measurable.measure(constraints.offset(horizontal = -insetPx))
        layout(placeable.width + insetPx, placeable.height) { placeable.placeRelative(insetPx, 0) }
    }
}

/**
 * Main bottom bar, chosen independently of [AppUiStyle]. Stored in the pre-existing
 * `KEY_USE_FLOATING_BOTTOM_BAR` + `KEY_FLOATING_BAR_STYLE` pair, so older backups keep meaning:
 * floating + style 1 is the iOS-like liquid bar in both styles; otherwise OS4 shows the upstream
 * glass bar and OS3 the classic or floating Miuix bar.
 */
enum class BottomBarStyle { Glass, Miuix, MiuixFloating, IosLiquid }

fun bottomBarStyle(ui: AppUiStyle, useFloating: Boolean, floatingStyle: Int): BottomBarStyle = when {
    useFloating && floatingStyle == 1 -> BottomBarStyle.IosLiquid
    ui == AppUiStyle.Os4 -> BottomBarStyle.Glass
    useFloating -> BottomBarStyle.MiuixFloating
    else -> BottomBarStyle.Miuix
}

/** The styles offered for [ui], in menu order. */
fun bottomBarStyles(ui: AppUiStyle): List<BottomBarStyle> = when (ui) {
    AppUiStyle.Os4 -> listOf(BottomBarStyle.Glass, BottomBarStyle.IosLiquid)
    AppUiStyle.Os3 -> listOf(BottomBarStyle.Miuix, BottomBarStyle.MiuixFloating, BottomBarStyle.IosLiquid)
}

/** Stored (useFloating, floatingStyle) for [style]; Glass and Miuix both mean "not floating". */
fun BottomBarStyle.stored(): Pair<Boolean, Int> = when (this) {
    BottomBarStyle.Glass, BottomBarStyle.Miuix -> false to 0
    BottomBarStyle.MiuixFloating -> true to 0
    BottomBarStyle.IosLiquid -> true to 1
}

/** Whether the app's active scheme is dark (honours the in-app theme mode, unlike the system flag). */
@Composable
fun isAppInDarkTheme(): Boolean = MiuixTheme.colorScheme.surface.luminance() < 0.5f
