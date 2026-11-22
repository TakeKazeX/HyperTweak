package com.takekazex.hypertweak.ui.page.systemui.controlcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.byValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.takekazex.hypertweak.R
import com.takekazex.hypertweak.ui.chrome.AppOverlayDialog as OverlayDialog
import com.takekazex.hypertweak.ui.chrome.AppTextButton as TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
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
            val input = remember(show) { TextFieldState(String.format(java.util.Locale.ROOT, "%.1f", current)) }
            TextField(
                modifier = Modifier.padding(bottom = 16.dp),
                state = input,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = {
                    Text(stringResource(R.string.cc_tile_message_duration_unit),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = colorScheme.onSurfaceVariantActions)
                },
                inputTransformation = InputTransformation.byValue { previous, proposed ->
                    val candidate = proposed.toString().replace(',', '.')
                    if (candidate.matches(Regex("[0-9]*(\\.[0-9]?)?"))) candidate else previous
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
                        val resolved = (input.text.toString().toFloatOrNull() ?: current).coerceIn(0f, 5f)
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
