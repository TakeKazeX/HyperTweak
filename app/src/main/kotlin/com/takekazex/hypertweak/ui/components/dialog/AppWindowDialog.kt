package com.takekazex.hypertweak.ui.components.dialog

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.takekazex.hypertweak.ui.chrome.AppUiStyle
import com.takekazex.hypertweak.ui.chrome.LocalAppOverlayHost
import com.takekazex.hypertweak.ui.chrome.LocalAppUiStyle
import com.takekazex.hypertweak.ui.chrome.LocalGlassDialog
import com.takekazex.hypertweak.ui.chrome.LocalPageBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.window.WindowDialog

/** Keep the app language when a separate window installs its own Android composition locals. */
@Composable
internal fun AppWindowDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    maxWidth: Dp = DialogDefaults.MaxWidth,
    largeScreen: Boolean? = null,
    cornerRadius: Dp? = null,
    content: @Composable () -> Unit,
) {
    // OS4 pages host the upstream glass dialog in-page (it has the same sizing and back
    // handling); outside a page, and in OS3, the dialog keeps its own window.
    if (LocalAppUiStyle.current == AppUiStyle.Os4 && LocalAppOverlayHost.current) {
        GlassDialog(
            show = show,
            title = title,
            summary = summary,
            onDismissRequest = onDismissRequest,
            backdrop = LocalPageBackdrop.current,
            maxWidth = maxWidth,
            largeScreen = largeScreen,
            cornerRadius = cornerRadius,
        ) {
            CompositionLocalProvider(LocalGlassDialog provides true, content = content)
        }
        return
    }
    WindowDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
        maxWidth = maxWidth,
        largeScreen = largeScreen,
        cornerRadius = cornerRadius,
        content = localizedDialogContent(content),
    )
}

/** The glass preset dialogs use a platform window with the same locale boundary. */
@Composable
internal fun AppDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(),
        content = localizedDialogContent(content),
    )
}

@Composable
private fun localizedDialogContent(content: @Composable () -> Unit): @Composable () -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locales = LocalConfiguration.current.locales
    return {
        // Retain the window's dimensions and other configuration while restoring the app locale.
        val windowConfiguration = LocalConfiguration.current
        val configuration = remember(windowConfiguration, locales) {
            Configuration(windowConfiguration).apply { setLocales(locales) }
        }
        CompositionLocalProvider(
            LocalContext provides context,
            LocalResources provides resources,
            LocalConfiguration provides configuration,
            content = content,
        )
    }
}
