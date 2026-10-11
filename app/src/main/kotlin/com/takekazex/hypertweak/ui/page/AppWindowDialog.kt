package com.takekazex.hypertweak.ui.page

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
