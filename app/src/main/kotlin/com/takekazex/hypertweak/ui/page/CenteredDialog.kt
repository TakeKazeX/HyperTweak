package com.takekazex.hypertweak.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.window.WindowDialog

/** Shared centered presentation, including Miuix's window back-event bridge and dismissal. */
@Composable
fun CenteredDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    WindowDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
        largeScreen = true,
        maxWidth = DialogDefaults.MaxWidth,
        cornerRadius = 32.dp,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
    }
}
