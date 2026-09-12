package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A centred dialog.
 *
 * Miuix 0.9.3 cannot centre a dialog on a phone. Both `OverlayDialog` and `WindowDialog` pick their
 * presentation from an internal `DialogDefaults.isLargeScreen()` — the shipped `DialogContentLayout`
 * carries `getBottomCenter`, `bottomCornerRadius`, `bottomPadding` and `offset` for the phone branch
 * against `getCenter` and `scale` for the large-screen branch — and neither composable exposes a
 * parameter to override it. The `largeScreen` argument that would force the centred variant only
 * exists in later Miuix releases, not in the 0.9.3 this module pins.
 *
 * So this composes Miuix's own visual language (a `Card` using its dialog width, Miuix title and
 * summary typography, Miuix `TextButton`s in the content) inside a platform dialog that is genuinely
 * centred. If Miuix is ever upgraded, this can be replaced by passing `largeScreen = true` to the
 * library's dialog instead.
 */
@Composable
fun CenteredDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    if (!show) return

    // `usePlatformDefaultWidth = false` hands the sizing decision to us; the platform default would
    // otherwise cap the window at a width that ignores the card's own maximum.
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .widthIn(max = DialogDefaults.MaxWidth)
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                insideMargin = PaddingValues(0.dp)
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp)) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = MiuixTheme.textStyles.title4,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                    if (summary != null) {
                        if (title != null) Spacer(Modifier.height(4.dp))
                        Text(
                            text = summary,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    if (title != null || summary != null) Spacer(Modifier.height(12.dp))
                    content()
                }
            }
        }
    }
}
