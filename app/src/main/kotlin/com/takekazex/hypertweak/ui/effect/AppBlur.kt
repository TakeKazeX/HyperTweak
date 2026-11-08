package com.takekazex.hypertweak.ui.effect

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Persisted IDs follow this declaration order; uniform blur preserves the previous default. */
enum class AppBlurMode { Off, Uniform, Progressive }
val LocalAppBlurMode = staticCompositionLocalOf { AppBlurMode.Uniform }

/** Applies uniform blur when the global blur setting is enabled. */
@Composable
fun Modifier.appTextureBlur(
    backdrop: Backdrop,
    shape: Shape,
    blurRadius: Float = 25f,
    colors: BlurColors = BlurColors(),
    highlight: Highlight? = null,
    enabled: Boolean = true,
    fallbackColor: Color = MiuixTheme.colorScheme.surface,
): Modifier {
    if (!enabled) return this
    return if (LocalAppBlurMode.current == AppBlurMode.Off) {
        background(fallbackColor, shape)
    } else {
        textureBlur(backdrop, shape, blurRadius, colors = colors, highlight = highlight)
    }
}
