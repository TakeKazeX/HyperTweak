package com.takekazex.hypertweak.ui.page.systemui.controlcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import com.takekazex.hypertweak.ui.effect.AppOverlayDialog as OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

@Composable
internal fun TileStateMessageDurationDialog(
    show: Boolean,
    current: Float,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.cc_tile_message_duration_title),
        summary = stringResource(R.string.cc_tile_message_duration_input_summary),
        onDismissRequest = onDismiss,
        content = {
            var text by remember(show) { mutableStateOf(java.lang.String.format(java.util.Locale.ROOT, "%.1f", current)) }
            TextField(
                modifier = Modifier.padding(bottom = 16.dp),
                value = text,
                maxLines = 1,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = {
                    Text(stringResource(R.string.cc_tile_message_duration_unit),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = colorScheme.onSurfaceVariantActions)
                },
                onValueChange = { newValue ->
                    val candidate = newValue.replace(',', '.')
                    if (candidate.matches(Regex("[0-9]*(\\.[0-9]?)?"))) text = candidate
                },
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.scale_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.scale_ok),
                    onClick = {
                        val resolved = (text.toFloatOrNull() ?: current).coerceIn(0f, 5f)
                        onConfirm(resolved)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    )
}
