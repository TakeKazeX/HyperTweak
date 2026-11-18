package com.takekazex.hypertweak.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.luminance
import com.takekazex.hypertweak.ui.effect.AppBlurMode
import com.takekazex.hypertweak.ui.effect.LocalAppBlurMode
import top.yukonga.miuix.kmp.blur.LayerBackdrop
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
 * Recording of the current page behind its chrome, provided by [AppPage]. Top bar, glass
 * buttons, dialogs and dropdowns sample it; null means blur is off and they use opaque fills.
 */
internal val LocalPageBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Draw-phase title opacity for chrome that covers the top bar title (the glass rail). */
val LocalTopBarTitleAlpha = staticCompositionLocalOf<() -> Float> { { 1f } }

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
