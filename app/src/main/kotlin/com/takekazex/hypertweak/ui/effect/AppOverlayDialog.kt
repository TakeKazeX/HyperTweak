package com.takekazex.hypertweak.ui.effect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalAppOverlayHost = staticCompositionLocalOf { false }

/** Miuix overlays have no window fallback. Fail at composition instead of silently disappearing. */
@Composable
internal fun AppOverlayDialog(
    show: Boolean,
    title: String,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    check(LocalAppOverlayHost.current) { "AppOverlayDialog must be inside AppScaffold" }
    top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
        content = content,
    )
}
